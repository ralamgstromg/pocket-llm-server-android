package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class PocketNodeService : Service() {

    private val server = PocketNodeServer()
    private var wifiLock: WifiManager.WifiLock? = null

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

            // Auto-resolve active chat model from allowlist if not set
            val allModels = PocketNodeModelResolver.getAllModels(this)
            if (PocketNodeState.activeChatModel == null && PocketNodeState.preferredChatModelName.isNotEmpty()) {
                PocketNodeState.activeChatModel = allModels.find { it.name.equals(PocketNodeState.preferredChatModelName, ignoreCase = true) }
            }
            if (PocketNodeState.activeChatModel == null && allModels.isNotEmpty()) {
                val downloaded = allModels.find { java.io.File(it.getPath(this)).exists() }
                PocketNodeState.activeChatModel = downloaded ?: allModels.first()
            }
            PocketNodeState.syncSharedModels()

            // Start Ktor HTTP Server
            server.start(this)
            acquireWifiLock()

            // Create the persistent notification required by Android for Foreground Services
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Pocket Node Active")
                .setContentText("Local HTTP server running on port ${PocketNodeServer.PORT} (Native STT + LLM)")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            // Tell the OS this service must not be killed while the screen is off
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForegroundWithMicrophoneIfAllowed(notification)
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
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        PocketNodeState.isServerRunning = false
    }

    /**
     * The native STT recognizer reads the caller's microphone access. From Android 12 a background app
     * cannot use the microphone unless it runs a foreground service of type `microphone`, so the service
     * declares that type whenever RECORD_AUDIO is granted. If the system rejects it (e.g. a restart from
     * the background), it falls back to `dataSync` so the HTTP server still runs.
     */
    private fun startForegroundWithMicrophoneIfAllowed(notification: Notification) {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Microphone foreground service type rejected; falling back to dataSync", e)
            }
        }
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * Keeps Wi-Fi awake while the screen is off. A foreground service alone does not stop Wi-Fi power
     * save. Plain WIFI_MODE_FULL is used because the LOW_LATENCY/HIGH_PERF modes are ignored on
     * Android 10+ unless the app is visible.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "PocketNode::WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
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
