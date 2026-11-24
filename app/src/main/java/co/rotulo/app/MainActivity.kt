package co.rotulo.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
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

/**
 * Contenedor nativo de Rótulo. La interfaz y el motor de edición son la misma
 * web app (assets/www/index.html), servida desde un origen https local
 * (appassets.androidplatform.net) para que el navegador interno habilite
 * Canvas.captureStream, MediaRecorder y AudioContext sin restricciones.
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

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            )
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShareIntent(intent)

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/shared/", SharedVideoHandler())
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
        web.addJavascriptInterface(RotuloBridge(this), "RotuloAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == HOST) return false
                // Enlaces externos: se abren en el navegador del teléfono
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
            if (web.canGoBack()) web.goBack() else finish()
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
        sharedName = queryName(uri) ?: "video.mp4"
        return true
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

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
}
