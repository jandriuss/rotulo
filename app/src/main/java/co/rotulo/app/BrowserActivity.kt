package co.rotulo.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback

/**
 * Navegador con detector de videos. El usuario abre la página y reproduce el video; mientras
 * tanto se registran los archivos de video que la página carga (MP4, WebM, MKV, MOV, FLV, HLS
 * .m3u8, DASH .mpd). Después se elige cuál descargar y se le pasa a yt-dlp con la página de
 * origen, las cookies y el agente de usuario. No evade protecciones DRM.
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private val MEDIA = Regex("""\.(mp4|m4v|webm|mkv|mov|flv|m3u8|mpd)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private val SKIP = Regex("""(\.(ts|m4s|aac|vtt|srt|jpg|jpeg|png|gif|webp|css|js)(\?|$))|(/ads?/)|(doubleclick|googlesyndication)""", RegexOption.IGNORE_CASE)
    }

    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var foundBtn: Button
    private lateinit var root: FrameLayout
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val found = LinkedHashMap<String, Pair<String, String>>()   // url → (tipo, página)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#0F1013"); val panel = Color.parseColor("#16171B")

        address = EditText(this).apply {
            setSingleLine(); hint = "Escribe o pega la dirección"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO; textSize = 15f
            setOnEditorActionListener { _, id, ev -> if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { go(text.toString()); true } else false }
        }
        val goBtn = Button(this).apply { text = "Ir"; setOnClickListener { go(address.text.toString()) } }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel); setPadding(16, 8, 8, 8); gravity = Gravity.CENTER_VERTICAL
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(goBtn)
        }
        web = WebView(this)
        foundBtn = Button(this).apply { text = "Videos detectados (0)"; isEnabled = false; setOnClickListener { chooseFound() } }
        val pageBtn = Button(this).apply { text = "Descargar esta página"; setOnClickListener { downloadPage() } }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(panel); setPadding(8, 8, 8, 8)
            addView(foundBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pageBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root = FrameLayout(this).apply { addView(col) }
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            loadWithOverviewMode = true; useWideViewPort = true
            builtInZoomControls = true; displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (MEDIA.containsMatchIn(u) && !SKIP.containsMatchIn(u)) {
                    val type = when {
                        u.contains(".m3u8", true) -> "Transmisión HLS"
                        u.contains(".mpd", true) -> "Transmisión DASH"
                        else -> MEDIA.find(u)?.groupValues?.get(1)?.uppercase() ?: "Video"
                    }
                    val key = u.substringBefore("&range=").substringBefore("?range=")
                    runOnUiThread { addFound(key, type) }
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: ""
                return !(s == "http" || s == "https")     // ignora intent:, market:, etc.
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { address.setText(url) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullView = view; fullCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            override fun onHideCustomView() { exitFull() }
        }
        onBackPressedDispatcher.addCallback(this) {
            when { fullView != null -> exitFull(); web.canGoBack() -> web.goBack(); else -> finish() }
        }
        go(intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com")
        Toast.makeText(this, "Abre la página y dale play al video: los archivos detectados aparecen abajo.", Toast.LENGTH_LONG).show()
    }

    private fun exitFull() {
        fullView?.let { root.removeView(it) }; fullView = null
        fullCallback?.onCustomViewHidden(); fullCallback = null
    }

    private fun go(raw: String) {
        val t = raw.trim(); if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(url)
    }

    private fun addFound(url: String, type: String) {
        if (found.containsKey(url)) return
        found[url] = type to (web.url ?: "")
        foundBtn.text = "Videos detectados (${found.size})"; foundBtn.isEnabled = true
    }

    private fun chooseFound() {
        val items = found.entries.reversed()
        val labels = items.map { (u, v) ->
            val name = Uri.parse(u).lastPathSegment?.take(40) ?: u.take(40)
            "${v.first} · $name"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige el video a descargar")
            .setItems(labels) { _, i -> val (u, v) = items[i]; startDownload(u, v.second, labelFromPage()) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun labelFromPage(): String = (web.title ?: "").ifBlank { "Video" }.take(100)

    private fun downloadPage() {
        val page = web.url ?: return
        startDownload(page, page, labelFromPage())
    }

    private fun startDownload(url: String, page: String, label: String) {
        val cookies = CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance().getCookie(page)
        Downloader.start(applicationContext, url, "video", "max", page, cookies, web.settings.userAgentString, label)
        Toast.makeText(this, "Descarga iniciada: la ves en Rótulo › Descargar", Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
