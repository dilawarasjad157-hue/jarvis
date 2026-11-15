package com.jarvis.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the app in "foreground" state so Android lets the microphone keep working in the
 * background. The actual listening happens in JarvisService (always-on loop).
 */
class MicService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("jarvis_mic", "Jarvis sun raha hai", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, "jarvis_mic")
            .setContentTitle("J.A.R.V.I.S. standing by")
            .setContentText("\"Jarvis\" bol kar baat shuru karein")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // Android refused (app not visible / mic permission missing). Stop quietly.
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, MicService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, MicService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
