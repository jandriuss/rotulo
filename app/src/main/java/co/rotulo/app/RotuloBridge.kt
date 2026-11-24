package co.rotulo.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
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

    // --- Audio nativo por partes ---
    /** true si el video abierto en la web es el mismo que la app puede leer de forma nativa. */
    @JavascriptInterface
    fun nativeAudioFor(name: String, size: Double): Boolean {
        if (activity.currentUri == null) return false
        val sameName = activity.currentName.isNotBlank() && activity.currentName == name
        val sameSize = activity.currentSize > 0 && activity.currentSize == size.toLong()
        return sameName || sameSize
    }

    // --- Trabajo en segundo plano ---
    @JavascriptInterface
    fun workStart(title: String) {
        activity.requestNotificationPermission()
        activity.runOnUiThread {
            try {
                activity.startForegroundService(Intent(activity, WorkService::class.java).putExtra("title", title))
            } catch (_: Exception) { }
        }
    }

    @JavascriptInterface
    fun workUpdate(text: String, percent: Int) {
        try { WorkService.update(activity, text, percent) } catch (_: Exception) { }
    }

    @JavascriptInterface
    fun workEnd(text: String, ok: Boolean) {
        try { activity.stopService(Intent(activity, WorkService::class.java)) } catch (_: Exception) { }
        try { WorkService.finished(activity, text) } catch (_: Exception) { }
    }

    /** true si Android puede restringir la app en segundo plano por ahorro de batería. */
    @JavascriptInterface
    fun batteryRestricted(): Boolean = try {
        !activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    } catch (_: Exception) { false }

    @JavascriptInterface
    fun openBatterySettings() {
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Exception) {
                try { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { }
            }
        }
    }

    // --- Video recibido desde "Compartir" ---
    @JavascriptInterface fun hasShared(): Boolean = activity.sharedUri != null
    @JavascriptInterface fun sharedName(): String = activity.sharedName
    @JavascriptInterface fun clearShared() { activity.sharedUri = null }

    private fun safeName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "rotulo.mp4" }
}
