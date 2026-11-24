package co.rotulo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servicio en primer plano que mantiene viva la app mientras hay trabajo (subtítulos o
 * descargas), con wake lock parcial para que el procesador no se duerma con la pantalla
 * apagada. Cada trabajo muestra su propia notificación de avance.
 */
class WorkService : Service() {

    companion object {
        const val CHANNEL = "rotulo_trabajo"
        const val NOTIF_ID = 4210
        private val active = AtomicInteger(0)

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Trabajo en segundo plano", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Avance de subtítulos y descargas" }
                )
            }
        }

        private fun openApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun build(ctx: Context, title: String, text: String, percent: Int, ongoing: Boolean): Notification {
            ensureChannel(ctx)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rotulo)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(ctx))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
            if (ongoing) b.setProgress(100, percent.coerceIn(0, 100), percent < 0)
            return b.build()
        }

        private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

        /** Empieza (o se suma) al trabajo en segundo plano. */
        fun acquire(ctx: Context, title: String) {
            if (active.incrementAndGet() == 1) {
                try {
                    ctx.startForegroundService(Intent(ctx, WorkService::class.java).putExtra("title", title))
                } catch (_: Exception) { }
            }
        }

        /** Termina un trabajo; el servicio se detiene cuando no queda ninguno. */
        fun release(ctx: Context) {
            if (active.decrementAndGet() <= 0) {
                active.set(0)
                try { ctx.stopService(Intent(ctx, WorkService::class.java)) } catch (_: Exception) { }
            }
        }

        fun progress(ctx: Context, id: Int, title: String, text: String, percent: Int) {
            try { nm(ctx).notify(id, build(ctx, title, text, percent, true)) } catch (_: Exception) { }
        }

        fun done(ctx: Context, id: Int, title: String, text: String) {
            try { nm(ctx).cancel(id); nm(ctx).notify(id + 1, build(ctx, title, text, 100, false)) } catch (_: Exception) { }
        }

        fun clear(ctx: Context, id: Int) { try { nm(ctx).cancel(id) } catch (_: Exception) { } }

        // Compatibilidad con la generación de subtítulos
        fun update(ctx: Context, text: String, percent: Int) = progress(ctx, NOTIF_ID, "Rótulo: generando subtítulos", text, percent)
        fun finished(ctx: Context, text: String) = done(ctx, NOTIF_ID, "Rótulo", text)
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Trabajando"
        startForeground(
            NOTIF_ID - 1,
            build(this, "Rótulo está trabajando", title, -1, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rotulo:trabajo")
                .apply { acquire(8 * 60 * 60 * 1000L) }   // máximo 8 horas por seguridad
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }
}
