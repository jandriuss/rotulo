package co.rotulo.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import java.io.OutputStream

/**
 * Puente JavaScript ↔ Android (window.RotuloAndroid).
 * El video exportado llega por partes en Base64 y se escribe directamente
 * en la Galería (Movies/Rotulo) mediante MediaStore, sin permisos extra.
 */
class RotuloBridge(private val activity: MainActivity) {

    private var out: OutputStream? = null
    private var uri: Uri? = null

    @JavascriptInterface
    fun begin(name: String, mime: String): Boolean {
        abort()
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, safeName(name))
                put(MediaStore.Video.Media.MIME_TYPE, if (mime.startsWith("video/")) mime else "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Rotulo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val u = activity.contentResolver.insert(collection, values) ?: return false
            uri = u
            out = activity.contentResolver.openOutputStream(u)
            out != null
        } catch (_: Exception) {
            abort(); false
        }
    }

    @JavascriptInterface
    fun append(chunkB64: String): Boolean = try {
        out?.write(Base64.decode(chunkB64, Base64.DEFAULT)); out != null
    } catch (_: Exception) { false }

    @JavascriptInterface
    fun end(): String {
        val u = uri ?: return ""
        return try {
            out?.flush(); out?.close(); out = null
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            activity.contentResolver.update(u, values, null, null)
            uri = null
            u.toString()
        } catch (_: Exception) {
            abort(); ""
        }
    }

    @JavascriptInterface
    fun abort() {
        try { out?.close() } catch (_: Exception) { }
        out = null
        uri?.let { try { activity.contentResolver.delete(it, null, null) } catch (_: Exception) { } }
        uri = null
    }

    @JavascriptInterface
    fun share(uriStr: String, mime: String) {
        if (uriStr.isBlank()) return
        activity.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (mime.startsWith("video/")) mime else "video/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "Compartir video"))
        }
    }

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) {
        activity.runOnUiThread {
            if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Guarda un archivo de texto (subtítulos .srt) en Descargas/Rotulo. Devuelve la URI o "". */
    @JavascriptInterface
    fun saveText(name: String, content: String): String = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(name))
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Rotulo")
        }
        val u = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (u == null) "" else {
            val os = activity.contentResolver.openOutputStream(u)
            if (os == null) "" else {
                os.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                u.toString()
            }
        }
    } catch (_: Exception) { "" }

    // --- Video recibido desde "Compartir" ---
    @JavascriptInterface fun hasShared(): Boolean = activity.sharedUri != null
    @JavascriptInterface fun sharedName(): String = activity.sharedName
    @JavascriptInterface fun clearShared() { activity.sharedUri = null }

    private fun safeName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "rotulo.mp4" }
}
