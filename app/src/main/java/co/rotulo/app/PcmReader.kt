package co.rotulo.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Lee la pista de audio de un video POR PARTES y la entrega como PCM de 16 kHz, mono, 16 bits,
 * que es lo que necesita Whisper. Nunca carga el archivo completo en memoria, así que sirve para
 * películas de varias horas.
 *
 * Remuestreo: promedio de las muestras de origen que caen en cada intervalo de salida
 * (filtro de caja + diezmado). Es suficiente para reconocimiento de voz.
 */
class PcmReader(context: Context, uri: Uri, startMs: Long) : Closeable {

    companion object { const val OUT_RATE = 16000 }

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    var srcRate: Int; private set
    var channels: Int; private set
    val mime: String
    val durationUs: Long
    val startSec: Double

    private var isFloat = false
    private var step: Double
    private var inputDone = false
    private var outputDone = false

    // Muestras mono de origen pendientes de convertir (posición fraccional en `pos`)
    private var src = FloatArray(1 shl 16)
    private var srcLen = 0
    private var pos = 0.0

    init {
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { extractor.release(); throw IllegalStateException("El video no tiene pista de audio") }
        extractor.selectTrack(track)
        val fmt = extractor.getTrackFormat(track)
        mime = fmt.getString(MediaFormat.KEY_MIME) ?: "audio/*"
        srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        step = srcRate.toDouble() / OUT_RATE

        if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val t = extractor.sampleTime
        startSec = if (t >= 0) t / 1_000_000.0 else startMs / 1000.0

        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** Devuelve hasta `maxSamples` muestras de 16 kHz. Un arreglo vacío significa fin del audio. */
    fun read(maxSamples: Int): ShortArray {
        val out = ShortArray(maxSamples)
        var n = 0
        while (n < maxSamples) {
            // producir todo lo que alcance con lo ya decodificado
            while (n < maxSamples && pos + step <= srcLen) {
                val a = pos.toInt()
                val b = (pos + step).toInt().coerceAtMost(srcLen).coerceAtLeast(a + 1)
                var acc = 0f
                for (i in a until b) acc += src[i]
                val v = (acc / (b - a)) * 32767f
                out[n++] = v.coerceIn(-32768f, 32767f).toInt().toShort()
                pos += step
            }
            if (n >= maxSamples) break
            compact()
            if (outputDone) {
                // vaciar lo último que quedó
                if (srcLen > pos.toInt() && n < maxSamples) { pos = srcLen.toDouble() }
                break
            }
            pump()
        }
        return if (n == maxSamples) out else out.copyOf(n)
    }

    /** Un paso de decodificación: alimenta el decodificador y recoge una salida si está lista. */
    private fun pump() {
        if (!inputDone) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                val buf = codec.getInputBuffer(ii)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(ii, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val oi = codec.dequeueOutputBuffer(info, 10_000)
        if (oi >= 0) {
            if (info.size > 0) {
                val ob = codec.getOutputBuffer(oi)!!
                ob.position(info.offset); ob.limit(info.offset + info.size)
                append(ob.slice().order(ByteOrder.nativeOrder()))
            }
            codec.releaseOutputBuffer(oi, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            step = srcRate.toDouble() / OUT_RATE
        }
    }

    /** Convierte la salida intercalada del decodificador a mono flotante y la agrega al búfer. */
    private fun append(bb: java.nio.ByteBuffer) {
        val ch = channels.coerceAtLeast(1)
        val frames = if (isFloat) bb.remaining() / 4 / ch else bb.remaining() / 2 / ch
        ensure(srcLen + frames)
        if (isFloat) {
            val fb = bb.asFloatBuffer()
            for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(); src[srcLen++] = s / ch }
        } else {
            val sb = bb.asShortBuffer()
            for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get() / 32768f; src[srcLen++] = s / ch }
        }
    }

    private fun ensure(cap: Int) { if (cap > src.size) src = src.copyOf(maxOf(cap, src.size * 2)) }

    /** Descarta las muestras ya consumidas para que el búfer no crezca. */
    private fun compact() {
        val drop = pos.toInt()
        if (drop > 0) {
            System.arraycopy(src, drop, src, 0, srcLen - drop)
            srcLen -= drop; pos -= drop
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { extractor.release() } catch (_: Exception) { }
    }
}
