package co.rotulo.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ejecuta el FFmpeg NATIVO (incluido en youtubedl-android) con varios núcleos: es mucho más
 * rápido que la versión WebAssembly. El video elegido se pasa como /proc/<pid>/fd/<n>, así
 * FFmpeg lo lee directamente (con búsqueda) sin copiarlo.
 *
 * Desde la web: argumentos con "{IN}" (video actual) y "@out/nombre" (archivos de salida).
 */
object NativeFfmpeg {

    class Job(val id: String) {
        @Volatile var process: Process? = null
        @Volatile var done = false
        @Volatile var rc = -1
        @Volatile var cancelled = false
        val lines = ArrayDeque<String>()
        var n = 0
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile var lastPcmRc = 0

    private fun bin(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    // Igual que youtubedl-android: FFmpeg necesita también las librerías del paquete de Python
    // (ahí viene libc++_shared.so). Sin esto falla con "CANNOT LINK EXECUTABLE".
    private fun libPath(ctx: Context): String {
        val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(pk, "$it/usr/lib").absolutePath } +
            ":" + ctx.applicationInfo.nativeLibraryDir
    }
    fun outDir(ctx: Context) = File(ctx.cacheDir, "ff").apply { mkdirs() }

    fun ready(ctx: Context) = RotuloApp.ready && bin(ctx).exists()

    private fun inputPath(pfd: ParcelFileDescriptor) = "/proc/${android.os.Process.myPid()}/fd/${pfd.fd}"

    private fun builder(ctx: Context, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(listOf(bin(ctx).absolutePath) + args)
        pb.environment()["LD_LIBRARY_PATH"] = libPath(ctx)
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
        return pb
    }

    fun start(ctx: Context, uri: Uri?, argsJson: String): String {
        val arr = JSONArray(argsJson)
        val id = UUID.randomUUID().toString()
        val job = Job(id)
        jobs[id] = job
        var pfd: ParcelFileDescriptor? = null
        val args = ArrayList<String>()
        try {
            for (i in 0 until arr.length()) {
                val a = arr.getString(i)
                when {
                    a == "{IN}" -> {
                        if (pfd == null) pfd = ctx.contentResolver.openFileDescriptor(uri ?: throw IllegalStateException("no hay video abierto"), "r")
                        args.add(inputPath(pfd!!))
                    }
                    a.startsWith("@out/") -> args.add(File(outDir(ctx), a.removePrefix("@out/").replace('/', '_')).absolutePath)
                    else -> args.add(a)
                }
            }
        } catch (e: Exception) {
            synchronized(job) { job.lines.addLast("error: ${e.message}"); job.n++ }
            job.rc = -1; job.done = true
            try { pfd?.close() } catch (_: Exception) { }
            return id
        }
        val inputFd = pfd
        Thread({
            try {
                val p = builder(ctx, args).redirectErrorStream(true).start()
                job.process = p
                p.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val l = r.readLine() ?: break
                        synchronized(job) {
                            job.lines.addLast(l)
                            if (job.lines.size > 80) job.lines.removeFirst()
                            job.n++
                        }
                    }
                }
                job.rc = p.waitFor()
            } catch (e: Exception) {
                synchronized(job) { job.lines.addLast("error: ${e.message}"); job.n++ }
                job.rc = -1
            } finally {
                try { inputFd?.close() } catch (_: Exception) { }
                job.done = true
            }
        }, "ffmpeg-$id").start()
        return id
    }

    /** Copia rápida a MP4 con MediaExtractor + MediaMuxer, como trabajo consultable con status(). */
    fun startRemux(ctx: Context, uri: Uri?): String {
        val id = UUID.randomUUID().toString()
        val job = Job(id); jobs[id] = job
        val add: (String) -> Unit = { l -> synchronized(job) { job.lines.addLast(l); if (job.lines.size > 80) job.lines.removeFirst(); job.n++ } }
        if (uri == null) { add("error: no hay video abierto"); job.rc = -1; job.done = true; return id }
        Thread({
            try {
                job.rc = NativeMedia.remux(ctx, uri, File(outDir(ctx), "remux.mp4"), add) { job.cancelled }
            } catch (e: Exception) { add("error: ${e.message}"); job.rc = -1 } finally { job.done = true }
        }, "remux-$id").start()
        return id
    }

    fun status(id: String): String {
        val j = jobs[id] ?: return JSONObject().put("done", true).put("rc", -1).put("n", 1)
            .put("tail", JSONArray().put("trabajo no encontrado")).toString()
        synchronized(j) {
            val o = JSONObject().put("done", j.done).put("rc", j.rc).put("n", j.n)
                .put("cancelled", j.cancelled).put("tail", JSONArray(j.lines.toList()))
            if (j.done) jobs.remove(id)
            return o.toString()
        }
    }

    fun cancel(id: String) {
        jobs[id]?.let { it.cancelled = true; it.process?.destroy() }
    }

    private fun sec(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    /** Audio de un tramo como PCM s16le, 16 kHz, mono. */
    fun pcm(ctx: Context, uri: Uri, startMs: Long, durMs: Long): ByteArray {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("no se pudo abrir el video")
        pfd.use {
            val args = listOf(
                "-hide_banner", "-nostdin", "-loglevel", "error",
                "-ss", sec(startMs), "-i", inputPath(it), "-t", sec(durMs),
                "-map", "0:a:0", "-vn", "-sn", "-dn", "-ac", "1", "-ar", "16000", "-f", "s16le", "pipe:1"
            )
            val p = builder(ctx, args).start()
            val drain = Thread { try { p.errorStream.readBytes() } catch (_: Exception) { } }.apply { start() }
            val bytes = p.inputStream.readBytes()
            lastPcmRc = p.waitFor(); drain.join(2000)
            return bytes
        }
    }
}
