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

/**
 * Servicio en primer plano que mantiene viva la app mientras se generan subtítulos:
 * muestra una notificación con el avance y sostiene un wake lock parcial para que el
 * procesador no se duerma con la pantalla apagada.
 */
class WorkService : Service() {

    companion object {
        const val CHANNEL = "rotulo_trabajo"
        const val NOTIF_ID = 4210
        const val DONE_ID = 4211

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Generación de subtítulos", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Avance de los subtítulos automáticos" }
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

        fun update(ctx: Context, text: String, percent: Int) {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, build(ctx, "Rótulo: generando subtítulos", text, percent, true))
        }

        fun finished(ctx: Context, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIF_ID)
            nm.notify(DONE_ID, build(ctx, "Rótulo", text, 100, false))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Generando subtítulos"
        startForeground(
            NOTIF_ID,
            build(this, "Rótulo: generando subtítulos", title, 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rotulo:subtitulos")
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
