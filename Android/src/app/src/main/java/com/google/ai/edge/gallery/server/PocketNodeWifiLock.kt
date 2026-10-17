package com.google.ai.edge.gallery.server

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Keeps Wi-Fi awake for inbound API calls while the screen is off. Held only while the API is active
 * (see `PocketNodeInference.touchActivity`), unless the user enables "keep Wi-Fi awake", in which case
 * the service holds it for its whole uptime. Plain WIFI_MODE_FULL is used because the LOW_LATENCY and
 * HIGH_PERF modes are ignored on Android 10+ unless the app is visible.
 */
internal object PocketNodeWifiLock {
    private var lock: WifiManager.WifiLock? = null

    @Synchronized
    fun hold(appContext: Context) {
        val current = lock ?: run {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "PocketNode::WifiLock")
                .apply { setReferenceCounted(false) }
                .also { lock = it }
        }
        if (!current.isHeld) current.acquire()
    }

    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}
