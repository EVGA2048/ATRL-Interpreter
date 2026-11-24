package net.astrolab.interpreter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

class CaptureService : Service() {
    companion object {
        @Volatile var stopRequested: (() -> Unit)? = null
        private const val CHANNEL = "interpreter_capture"
        private const val STOP = "net.astrolab.interpreter.STOP_CAPTURE"
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRequested?.invoke() ?: stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Interpreter · 麦克风", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Interpreter")
            .setContentText("实时传译会话正在运行")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_menu_close_clear_cancel),"结束会话",stop).build())
            .build()
        startForeground(1204, notification)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        stopRequested?.invoke()
        stopRequested = null
        super.onDestroy()
    }
}
