package co.rotulo.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Descargas con yt-dlp (youtubedl-android, la misma base de Seal).
 * - Sitios conocidos por yt-dlp y extractor genérico para otras páginas.
 * - Enlaces directos o transmisiones (MP4, WebM, HLS .m3u8, DASH .mpd) detectados por el navegador,
 *   con la página de origen, la cookie y el agente de usuario para que el servidor los acepte.
 * - Se prefiere MP4 con H.264 para que el video abra directo en Rótulo.
 * El resultado se guarda en Movies/Rotulo (video) o Music/Rotulo (audio).
 */
object Downloader {

    class Job(
        val id: String, val url: String, val kind: String, val quality: String,
        val referer: String?, val cookies: String?, val userAgent: String?, label: String?
    ) {
        @Volatile var state = "starting"
        @Volatile var progress = 0f
        @Volatile var line = ""
        @Volatile var title: String = label ?: url
        @Volatile var uri: String? = null
        @Volatile var size = 0L
        @Volatile var error: String? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val order = CopyOnWriteArrayList<String>()
    @Volatile var listener: ((JSONObject) -> Unit)? = null
    @Volatile var updating = false

    private fun json(j: Job) = JSONObject()
        .put("id", j.id).put("url", j.url).put("kind", j.kind).put("state", j.state)
        .put("progress", j.progress.toDouble()).put("line", j.line).put("title", j.title)
        .put("uri", j.uri ?: JSONObject.NULL).put("size", j.size).put("error", j.error ?: JSONObject.NULL)

    private fun emit(j: Job) { try { listener?.invoke(json(j)) } catch (_: Exception) { } }

    fun list(): String = JSONArray().apply { order.mapNotNull { jobs[it] }.forEach { put(json(it)) } }.toString()

    fun engine(ctx: Context): JSONObject = JSONObject()
        .put("ready", RotuloApp.ready)
        .put("error", RotuloApp.error ?: JSONObject.NULL)
        .put("ytdlp", RotuloApp.ytdlpVersion ?: JSONObject.NULL)
        .put("updating", updating)

    fun start(
        ctx: Context, url: String, kind: String, quality: String,
        referer: String? = null, cookies: String? = null, userAgent: String? = null, label: String? = null
    ): String {
        val app = ctx.applicationContext
        val id = "dl" + System.currentTimeMillis().toString(36)
        val j = Job(id, url, if (kind == "audio") "audio" else "video", quality, referer, cookies, userAgent, label)
        jobs[id] = j; order.add(0, id)
        emit(j)
        WorkService.acquire(app, "Descargando video")
        Thread({
            try { run(app, j) } finally {
                WorkService.clear(app, notifId(j))
                WorkService.release(app)
            }
        }, "descarga-$id").start()
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.let { it.state = "cancelled"; emit(it) }
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (_: Exception) { }
    }

    private fun notifId(j: Job) = 5000 + (j.id.hashCode() and 0x3ff)

    private fun run(ctx: Context, j: Job) {
        try {
            if (!RotuloApp.awaitReady()) throw Exception(RotuloApp.error ?: "el motor de descargas no terminó de prepararse")
            autoUpdate(ctx)
            val dir = File(ctx.getExternalFilesDir(null), "descargas/${j.id}").apply { deleteRecursively(); mkdirs() }
            val req = YoutubeDLRequest(j.url)
                .addOption("--no-playlist")
                .addOption("--no-mtime")
                .addOption("--windows-filenames")
                .addOption("-o", dir.absolutePath + "/%(title).100B.%(ext)s")
            j.referer?.takeIf { it.isNotBlank() }?.let { req.addOption("--referer", it) }
            j.userAgent?.takeIf { it.isNotBlank() }?.let { req.addOption("--user-agent", it) }
            j.cookies?.takeIf { it.isNotBlank() }?.let { req.addOption("--add-header", "Cookie: $it") }
            if (j.kind == "audio") {
                req.addOption("-f", "ba[ext=m4a]/ba/b").addOption("-x").addOption("--audio-format", "m4a")
            } else {
                val h = if (j.quality == "max") "" else "[height<=${j.quality}]"
                req.addOption("-f", "bv*[vcodec^=avc1]$h+ba[ext=m4a]/b[ext=mp4]$h/bv*$h+ba/b$h/bv*+ba/b")
                    .addOption("--merge-output-format", "mp4")
            }
            j.state = "running"; emit(j)
            var last = 0L
            YoutubeDL.getInstance().execute(req, j.id) { p, _, line ->
                j.progress = p.coerceIn(0f, 100f); j.line = line
                val dest = Regex("""Destination:\s*(.+)$""").find(line)?.groupValues?.get(1)
                if (dest != null) j.title = File(dest.trim()).nameWithoutExtension.substringBeforeLast(".f")
                val now = SystemClock.uptimeMillis()
                if (now - last > 600) {
                    last = now; emit(j)
                    WorkService.progress(ctx, notifId(j), "Descargando: ${j.title}", line.removePrefix("[download]").trim(), j.progress.toInt())
                }
            }
            val f = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp") }
                ?.maxByOrNull { it.length() }
                ?: throw Exception("yt-dlp terminó sin producir un archivo")
            j.title = f.nameWithoutExtension
            j.state = "saving"; emit(j)
            val uri = saveToMediaStore(ctx, f, j.kind)
            j.size = f.length()
            dir.deleteRecursively()
            j.uri = uri.toString(); j.progress = 100f; j.state = "done"; emit(j)
            WorkService.done(ctx, notifId(j), "Descarga lista", j.title)
        } catch (e: YoutubeDL.CanceledException) {
            j.state = "cancelled"; emit(j)
        } catch (e: Throwable) {
            if (j.state == "cancelled") return
            j.error = friendly(e.message ?: e.toString()); j.state = "error"; emit(j)
            WorkService.done(ctx, notifId(j), "La descarga falló", j.title)
        }
    }

    private fun friendly(raw: String): String {
        val m = raw.trim()
        val tail = m.lines().filter { it.contains("ERROR", true) }.lastOrNull()?.take(300) ?: m.takeLast(300)
        val why = when {
            m.contains("DRM", true) -> "El video está protegido con DRM; no se puede descargar."
            m.contains("Unsupported URL", true) -> "yt-dlp no reconoce esta página. Prueba con «Buscar el video en la página»: ábrela en el navegador de Rótulo, reproduce el video y elige el archivo detectado."
            m.contains("Sign in", true) || m.contains("login", true) || m.contains("cookies", true) ->
                "La página pide iniciar sesión. Ábrela con «Buscar el video en la página», inicia sesión allí y descarga desde el navegador de Rótulo."
            m.contains("HTTP Error 403", true) -> "El servidor rechazó la descarga (403). Prueba actualizando yt-dlp o desde el navegador de Rótulo."
            m.contains("Unable to resolve host", true) || m.contains("Network is unreachable", true) -> "Sin conexión a internet."
            else -> "No se pudo descargar."
        }
        return "$why\nDetalle: $tail"
    }

    private fun autoUpdate(ctx: Context) {
        val prefs = ctx.getSharedPreferences("rotulo", Context.MODE_PRIVATE)
        val lastUpd = prefs.getLong("ytdlp_upd", 0L)
        if (System.currentTimeMillis() - lastUpd < 3L * 24 * 3600 * 1000) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
            RotuloApp.ytdlpVersion = YoutubeDL.getInstance().versionName(ctx) ?: RotuloApp.ytdlpVersion
            prefs.edit().putLong("ytdlp_upd", System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    /** Actualización manual de yt-dlp (los sitios cambian seguido). Avisa por el listener. */
    fun updateNow(ctx: Context, onEngine: (JSONObject, String) -> Unit) {
        if (updating) return
        updating = true
        onEngine(engine(ctx), "")
        Thread({
            var msg: String
            try {
                RotuloApp.awaitReady()
                val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                RotuloApp.ytdlpVersion = YoutubeDL.getInstance().versionName(ctx) ?: RotuloApp.ytdlpVersion
                ctx.getSharedPreferences("rotulo", Context.MODE_PRIVATE).edit().putLong("ytdlp_upd", System.currentTimeMillis()).apply()
                msg = if (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp ya estaba al día" else "yt-dlp actualizado"
            } catch (e: Exception) {
                msg = "No se pudo actualizar yt-dlp: ${e.message}"
            }
            updating = false
            onEngine(engine(ctx), msg)
        }, "ytdlp-update").start()
    }

    private fun mimeOf(f: File, kind: String): String = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> if (kind == "audio") "audio/webm" else "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        else -> if (kind == "audio") "audio/*" else "video/*"
    }

    private fun saveToMediaStore(ctx: Context, f: File, kind: String): Uri {
        val audio = kind == "audio"
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f, kind))
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Rotulo")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: throw Exception("no se pudo crear el archivo en la Galería")
        try {
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null); throw e
        }
        return uri
    }
}
