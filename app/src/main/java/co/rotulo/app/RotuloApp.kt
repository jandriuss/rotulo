package co.rotulo.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Prepara al arrancar, en segundo plano, el motor de descargas (Python + yt-dlp) y el
 * FFmpeg nativo que trae youtubedl-android. La primera vez descomprime los binarios
 * (tarda unos segundos); las siguientes es casi inmediato.
 */
class RotuloApp : Application() {

    companion object {
        @Volatile var ready = false
        @Volatile var error: String? = null
        @Volatile var ytdlpVersion: String? = null
        private val latch = CountDownLatch(1)

        fun awaitReady(timeoutMs: Long = 120_000): Boolean {
            try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            return ready
        }
    }

    override fun onCreate() {
        super.onCreate()
        Thread({
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                ytdlpVersion = YoutubeDL.getInstance().versionName(this) ?: YoutubeDL.getInstance().version(this)
                ready = true
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                latch.countDown()
            }
        }, "rotulo-init").start()
    }
}
