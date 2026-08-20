package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PocketNodeService : Service() {

    private val server = PocketNodeServer()

    companion object {
        const val CHANNEL_ID = "PocketNodeServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load stored model preferences
        PocketNodeState.loadPreferences(this)

        // Start Ktor HTTP Server
        server.start(this)

        // Create the persistent notification required by Android for Foreground Services
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Pocket Node Active")
            .setContentText("Local HTTP server running on port 8080")
            // Note: Replace with a valid drawable icon in the future if this crashes
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .build()

        // Tell the OS this service must not be killed while the screen is off
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need binding, just a started service
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Pocket Node Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
