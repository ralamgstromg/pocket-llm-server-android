package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class PocketNodeService : Service() {

    private val server = PocketNodeServer()

    companion object {
        private const val TAG = "PocketNodeService"
        const val CHANNEL_ID = "PocketNodeServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Load stored model preferences
            PocketNodeState.loadPreferences(this)

            // Auto-resolve active models from allowlist if not set
            val allModels = PocketNodeModelResolver.getAllModels(this)
            if (PocketNodeState.activeChatModel == null && PocketNodeState.preferredChatModelName.isNotEmpty()) {
                PocketNodeState.activeChatModel = allModels.find { it.name.equals(PocketNodeState.preferredChatModelName, ignoreCase = true) }
            }
            if (PocketNodeState.activeAudioModel == null && PocketNodeState.preferredAudioModelName.isNotEmpty()) {
                PocketNodeState.activeAudioModel = allModels.find { it.name.equals(PocketNodeState.preferredAudioModelName, ignoreCase = true) }
            }
            if (PocketNodeState.activeChatModel == null && allModels.isNotEmpty()) {
                val downloaded = allModels.find { java.io.File(it.getPath(this)).exists() }
                PocketNodeState.activeChatModel = downloaded ?: allModels.first()
            }
            PocketNodeState.syncSharedModels()

            // Start Ktor HTTP Server
            server.start(this)

            // Create the persistent notification required by Android for Foreground Services
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Pocket Node Active")
                .setContentText("Local HTTP server running on port 8080")
                .setSmallIcon(android.R.drawable.ic_dialog_info) 
                .build()

            // Tell the OS this service must not be killed while the screen is off
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PocketNodeService foreground", e)
            PocketNodeState.isServerRunning = false
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
        PocketNodeState.isServerRunning = false
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
