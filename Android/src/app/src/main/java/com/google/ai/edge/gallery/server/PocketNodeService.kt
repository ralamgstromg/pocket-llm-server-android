package com.google.ai.edge.gallery.server

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.R

class PocketNodeService : Service() {

    private val server = PocketNodeServer()

    companion object {
        private const val TAG = "PocketNodeService"
        const val CHANNEL_ID = "PocketNodeServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.google.ai.edge.gallery.server.STOP"
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
        try {
            PocketNodeState.loadPreferences(this)

            // Start Ktor HTTP Server
            server.start(this)

            // Create the persistent notification required by Android for Foreground Services
            val notification = buildNotification()

            // Tell the OS this service must not be killed while the screen is off
            // specialUse has no daily runtime cap; dataSync (API 31-33) is capped on newer Android.
            val fgsType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PocketNodeService foreground", e)
            PocketNodeState.isServerRunning = false
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, PocketNodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val address = "${NetworkAddress.lanIp() ?: "localhost"}:${PocketNodeState.port}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pocket_node_notification_title))
            .setContentText(getString(R.string.pocket_node_notification_text, address))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.pocket_node_stop), stop)
            .build()
    }

    /** Called when a time-limited foreground service type runs out of time; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout (type $fgsType); stopping Pocket Node server.")
        PocketNodeState.lastStartupError = "Android stopped the server: foreground service time limit reached."
        stopSelf(startId)
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
