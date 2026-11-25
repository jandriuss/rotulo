package co.rotulo.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Análisis y "copia rápida" a MP4 con las herramientas propias de Android (MediaExtractor +
 * MediaMuxer). Leen el video por su URI, sin depender de rutas de archivo, y copian los datos
 * sin recodificar: sirve para .ts, .mkv, .mp4 con nombre engañoso, etc. con H.264/HEVC + AAC.
 */
object NativeMedia {

    fun codecName(mime: String): String = when (mime.lowercase(Locale.US)) {
        "video/avc" -> "h264"; "video/hevc" -> "hevc"; "video/x-vnd.on2.vp8" -> "vp8"
        "video/x-vnd.on2.vp9" -> "vp9"; "video/av01" -> "av1"; "video/mp4v-es" -> "mpeg4"
        "video/mpeg2" -> "mpeg2video"; "video/3gpp" -> "h263"
        "audio/mp4a-latm" -> "aac"; "audio/mpeg" -> "mp3"; "audio/opus" -> "opus"; "audio/vorbis" -> "vorbis"
        "audio/ac3" -> "ac3"; "audio/eac3" -> "eac3"; "audio/flac" -> "flac"; "audio/raw" -> "pcm"
        else -> mime.substringAfter('/')
    }

    fun probe(ctx: Context, uri: Uri): JSONObject {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            var v: MediaFormat? = null; var a: MediaFormat? = null; var durUs = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = maxOf(durUs, f.getLong(MediaFormat.KEY_DURATION))
                if (v == null && m.startsWith("video/")) v = f
                if (a == null && m.startsWith("audio/")) a = f
            }
            JSONObject().put("ok", true)
                .put("vcodec", v?.getString(MediaFormat.KEY_MIME)?.let { codecName(it) } ?: JSONObject.NULL)
                .put("acodec", a?.getString(MediaFormat.KEY_MIME)?.let { codecName(it) } ?: JSONObject.NULL)
                .put("w", if (v?.containsKey(MediaFormat.KEY_WIDTH) == true) v.getInteger(MediaFormat.KEY_WIDTH) else 0)
                .put("h", if (v?.containsKey(MediaFormat.KEY_HEIGHT) == true) v.getInteger(MediaFormat.KEY_HEIGHT) else 0)
                .put("dur", durUs / 1e6)
                .put("tracks", ex.trackCount)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        } finally {
            try { ex.release() } catch (_: Exception) { }
        }
    }

    private fun hhmmss(us: Long): String {
        val s = us / 1e6
        return String.format(Locale.US, "%02d:%02d:%05.2f", (s / 3600).toInt(), ((s % 3600) / 60).toInt(), s % 60)
    }

    /** Copia video (H.264/HEVC/AV1) y audio AAC a un MP4. Informa el avance como líneas "time=". */
    fun remux(ctx: Context, uri: Uri, out: File, log: (String) -> Unit, cancelled: () -> Boolean): Int {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, uri, null)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            var hasVideo = false; var hasAudio = false; var durUs = 1L; var maxIn = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                val okVideo = !hasVideo && (m == "video/avc" || m == "video/hevc" || m == "video/av01")
                val okAudio = !hasAudio && m == "audio/mp4a-latm"
                if (!okVideo && !okAudio) { log("pista omitida: $m"); continue }
                if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = maxOf(durUs, f.getLong(MediaFormat.KEY_DURATION))
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = maxOf(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                if (okVideo && f.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
                map[i] = muxer.addTrack(f)
                ex.selectTrack(i)
                if (okVideo) hasVideo = true else hasAudio = true
                log("pista copiada: $m")
            }
            if (!hasVideo) throw IllegalStateException("el video no es H.264/HEVC/AV1: no se puede copiar sin recodificar")
            muxer.start()
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            var first = Long.MIN_VALUE; var lastLog = 0L
            while (true) {
                if (cancelled()) return 255
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val dst = map[ti]
                if (dst != null) {
                    buf.clear()
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val t = ex.sampleTime
                    if (first == Long.MIN_VALUE) first = t
                    info.set(0, size, maxOf(0L, t - first),
                        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(dst, buf, info)
                    if (t - lastLog > 2_000_000) { lastLog = t; log("time=" + hhmmss(t - first)) }
                }
                ex.advance()
            }
            muxer.stop()
            log("time=" + hhmmss(durUs)); log("copia rápida terminada")
            return 0
        } catch (e: Exception) {
            log("error: " + (e.message ?: e.toString()))
            return 1
        } finally {
            try { muxer?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }
}
