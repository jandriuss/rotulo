package co.rotulo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.ByteArrayInputStream

/**
 * Contenedor nativo de Rótulo. La interfaz y el motor de subtítulos son la web app
 * (assets/www), servida desde un origen https local (appassets.androidplatform.net).
 *
 * Lo nativo aporta:
 *  - /pcm/…    lectura del audio del video por partes (PcmReader), para películas largas;
 *  - aislamiento entre orígenes (COOP/COEP) para que el motor de IA use varios núcleos;
 *  - servicio en primer plano para seguir trabajando en segundo plano (WorkService).
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/www/index.html"
    }

    lateinit var web: WebView
        private set

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Video recibido desde "Compartir" de otra app
    @Volatile var sharedUri: Uri? = null
    @Volatile var sharedMime: String = "video/mp4"
    @Volatile var sharedName: String = "video.mp4"

    // Último video abierto (para leer su audio de forma nativa)
    @Volatile var currentUri: Uri? = null
    @Volatile var currentName: String = ""
    @Volatile var currentSize: Long = -1

    // Archivo que se entregará al próximo "Abrir video" (videos recién descargados)
    @Volatile var pendingPick: Uri? = null
    // Enlace recibido desde "Compartir" (texto) para descargar
    @Volatile var sharedLink: String? = null

    private val pcmLock = Any()
    private var pcm: PcmReader? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            uris?.firstOrNull()?.let { rememberVideo(it) }
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    private val browser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) web.evaluateJavascript("window.showTab && showTab('dl')", null)
        }

    fun openBrowser(url: String) {
        runOnUiThread { browser.launch(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url)) }
    }

    fun pushJs(fn: String, arg: String) {
        runOnUiThread { try { web.evaluateJavascript("window.$fn && window.$fn($arg)", null) } catch (_: Exception) { } }
    }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShareIntent(intent)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", IsolatedHandler(WebViewAssetLoader.AssetsPathHandler(this)))
            .addPathHandler("/shared/", SharedVideoHandler())
            .addPathHandler("/pcm/", PcmHandler())
            .addPathHandler("/ff/", FfHandler())
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        // Que el motor de IA no pierda prioridad cuando la app no está visible
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.addJavascriptInterface(RotuloBridge(this), "RotuloAndroid")
        Downloader.listener = { o -> pushJs("__dlEvent", o.toString()) }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                // Copia MP4 hecha por FFmpeg nativo: se sirve por partes (Range) para que el
                // reproductor la lea sin cargarla completa en memoria
                if (u.host == HOST && (u.path ?: "").startsWith("/ff/stream/")) {
                    val name = (u.lastPathSegment ?: return null).replace("..", "")
                    return streamFile(File(NativeFfmpeg.outDir(this@MainActivity), name), request.requestHeaders)
                }
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: ActivityNotFoundException) { }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                // Video recién descargado: se entrega directo, sin abrir el selector
                pendingPick?.let { uri ->
                    pendingPick = null
                    rememberVideo(uri)
                    callback.onReceiveValue(arrayOf(uri))
                    return true
                }
                fileCallback = callback
                // Sin filtro de tipo: así aparecen también los .mkv, .avi, .ts, .rmvb, etc. que algunos
                // teléfonos no marcan como "video". Rótulo decide después si puede abrirlo.
                val pick = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                return try {
                    pickFile.launch(Intent.createChooser(pick, "Elegir video"))
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readShareIntent(intent)) {
            web.evaluateJavascript("window.__rotuloShared && window.__rotuloShared()", null)
        }
        sharedLink?.let { l -> sharedLink = null; pushJs("__rotuloLink", JSONObject.quote(l)) }
    }

    override fun onDestroy() {
        Downloader.listener = null
        synchronized(pcmLock) { pcm?.close(); pcm = null }
        super.onDestroy()
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runOnUiThread { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun rememberVideo(uri: Uri) {
        currentUri = uri
        var name = ""; var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: ""
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) { }
        currentName = name; currentSize = size
    }

    /** Guarda la referencia del video compartido; devuelve true si había uno. */
    private fun readShareIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val type = intent.type ?: return false
        if (type.startsWith("text/")) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
            sharedLink = Regex("https?://\\S+").find(text)?.value
            return false
        }
        if (!type.startsWith("video/")) return false
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) return false
        sharedUri = uri
        sharedMime = contentResolver.getType(uri) ?: type
        rememberVideo(uri)
        sharedName = currentName.ifBlank { "video.mp4" }
        return true
    }

    /** Respuestas JSON / binarias para los PathHandler. */
    private fun json(o: JSONObject) =
        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(o.toString().toByteArray()))
            .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }

    /**
     * Agrega las cabeceras de aislamiento (COOP/COEP) para habilitar SharedArrayBuffer y los
     * hilos del motor de IA, y corrige el tipo MIME de .mjs y .wasm.
     */
    private class IsolatedHandler(private val inner: WebViewAssetLoader.PathHandler) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val r = inner.handle(path) ?: return null
            when {
                path.endsWith(".mjs") || path.endsWith(".js") -> r.mimeType = "text/javascript"
                path.endsWith(".wasm") -> r.mimeType = "application/wasm"
                path.endsWith(".webmanifest") -> r.mimeType = "application/manifest+json"
            }
            r.responseHeaders = (r.responseHeaders ?: emptyMap()) + mapOf(
                "Cross-Origin-Opener-Policy" to "same-origin",
                "Cross-Origin-Embedder-Policy" to "credentialless",
                "Cross-Origin-Resource-Policy" to "same-origin"
            )
            return r
        }
    }

    /** Sirve el video compartido en https://appassets.androidplatform.net/shared/video */
    private inner class SharedVideoHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val uri = sharedUri ?: return null
            return try {
                val stream = contentResolver.openInputStream(uri) ?: return null
                WebResourceResponse(sharedMime, null, stream)
            } catch (_: Exception) { null }
        }
    }

    /**
     * Audio por partes para el motor de subtítulos:
     *   /pcm/open/<ms>   abre el video actual desde esa posición → JSON
     *   /pcm/next/<n>    hasta n muestras int16 little-endian a 16 kHz (vacío = fin)
     *   /pcm/close       libera el decodificador
     */
    private inner class PcmHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val parts = path.trim('/').split('/')
            return try {
                when (parts.getOrNull(0)) {
                    "open" -> synchronized(pcmLock) {
                        pcm?.close(); pcm = null
                        val uri = currentUri ?: return json(JSONObject().put("ok", false).put("error", "no hay video abierto"))
                        val r = PcmReader(this@MainActivity, uri, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                        pcm = r
                        json(JSONObject().put("ok", true).put("startSec", r.startSec).put("rate", r.srcRate)
                            .put("channels", r.channels).put("mime", r.mime).put("durationSec", r.durationUs / 1e6))
                    }
                    "next" -> synchronized(pcmLock) {
                        val r = pcm ?: return json(JSONObject().put("ok", false).put("error", "audio no abierto"))
                        val n = (parts.getOrNull(1)?.toIntOrNull() ?: 16000).coerceIn(1, 16000 * 120)
                        val s = r.read(n)
                        val bytes = ByteArray(s.size * 2)
                        for (i in s.indices) {
                            val v = s[i].toInt()
                            bytes[2 * i] = (v and 0xff).toByte()
                            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                        }
                        WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(bytes))
                            .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
                    }
                    "close" -> synchronized(pcmLock) {
                        pcm?.close(); pcm = null
                        json(JSONObject().put("ok", true))
                    }
                    else -> null
                }
            } catch (e: Exception) {
                synchronized(pcmLock) { try { pcm?.close() } catch (_: Exception) { }; pcm = null }
                json(JSONObject().put("ok", false).put("error", e.message ?: e.toString()))
            }
        }
    }

    /**
     * FFmpeg nativo para la web:
     *   /ff/pcm/<inicioMs>/<duraciónMs>  audio del video actual (s16le 16 kHz mono)
     *   /ff/out/<nombre>                 archivo producido por un trabajo (proxy, imagen, etc.)
     */
    private inner class FfHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val parts = path.trim('/').split('/')
            return try {
                when (parts.getOrNull(0)) {
                    "pcm" -> {
                        val uri = currentUri ?: return null
                        val bytes = NativeFfmpeg.pcm(this@MainActivity, uri, parts[1].toLong(), parts[2].toLong())
                        WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(bytes))
                            .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
                    }
                    "out" -> {
                        val name = parts.getOrNull(1)?.replace("..", "") ?: return null
                        val f = File(NativeFfmpeg.outDir(this@MainActivity), name)
                        if (!f.exists()) return null
                        val mime = when (f.extension.lowercase()) {
                            "mp4" -> "video/mp4"; "jpg" -> "image/jpeg"; "png" -> "image/png"; else -> "application/octet-stream"
                        }
                        WebResourceResponse(mime, null, FileInputStream(f)).apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
                    }
                    else -> null
                }
            } catch (e: Exception) { null }
        }
    }

    private fun streamFile(f: File, headers: Map<String, String>?): WebResourceResponse? {
        if (!f.exists()) return null
        val len = f.length()
        val range = headers?.entries?.firstOrNull { it.key.equals("Range", true) }?.value
        var start = 0L
        var end = len - 1
        if (range != null) {
            Regex("bytes=(\\d*)-(\\d*)").find(range)?.let { m ->
                if (m.groupValues[1].isNotEmpty()) start = m.groupValues[1].toLong()
                if (m.groupValues[2].isNotEmpty()) end = minOf(m.groupValues[2].toLong(), len - 1)
            }
        }
        if (start > end || start >= len) start = 0
        val count = end - start + 1
        val raf = RandomAccessFile(f, "r").apply { seek(start) }
        val body = object : InputStream() {
            var left = count
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raf.read(); if (b >= 0) left--; return b
            }
            override fun read(b: ByteArray, off: Int, l: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(l.toLong(), left).toInt()); if (n > 0) left -= n; return n
            }
            override fun close() { raf.close() }
        }
        val h = mutableMapOf(
            "Accept-Ranges" to "bytes", "Content-Length" to count.toString(),
            "Cache-Control" to "no-store", "Cross-Origin-Resource-Policy" to "same-origin"
        )
        return if (range != null) {
            h["Content-Range"] = "bytes $start-$end/$len"
            WebResourceResponse("video/mp4", null, 206, "Partial Content", h, body)
        } else WebResourceResponse("video/mp4", null, 200, "OK", h, body)
    }
}
