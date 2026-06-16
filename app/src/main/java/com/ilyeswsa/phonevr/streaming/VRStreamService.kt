package com.ilyeswsa.phonevr.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service to keep VR streaming alive when the app is backgrounded.
 * Keeps the UDP sockets and sensor listeners running even if the user
 * accidentally presses home.
 */
class VRStreamService : Service() {

    companion object {
        const val CHANNEL_ID = "phonevr_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.ilyeswsa.phonevr.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneVR Active")
            .setContentText("Streaming VR to PC…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PhoneVR Stream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VR streaming is active"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
