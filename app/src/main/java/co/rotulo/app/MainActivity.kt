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

    private val pcmLock = Any()
    private var pcm: PcmReader? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val uris = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            uris?.firstOrNull()?.let { rememberVideo(it) }
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
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

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

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
                fileCallback = callback
                return try {
                    pickFile.launch(params.createIntent())
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
    }

    override fun onDestroy() {
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
}
