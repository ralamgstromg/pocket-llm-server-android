package com.google.ai.edge.gallery.server

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.gallery.data.Model

object PocketNodeState {
    const val DEFAULT_MAX_TOKENS = 8000
    const val MIN_MAX_TOKENS = 256
    const val MAX_MAX_TOKENS = 32768

    const val DEFAULT_IDLE_TIMEOUT_MINUTES = 3
    const val MIN_IDLE_TIMEOUT_MINUTES = 1
    const val MAX_IDLE_TIMEOUT_MINUTES = 30

    var activeChatModel: Model? = null
    var activeAudioModel: Model? = null

    var preferredChatModelName: String = ""
    var preferredAudioModelName: String = AndroidNativeSttEngine.ENGINE_MODEL_ID

    // Observable Compose state for reactive UI updates
    var isServerRunning by mutableStateOf(false)
    var lastStartupError by mutableStateOf<String?>(null)
    var configuredMaxTokens by mutableIntStateOf(DEFAULT_MAX_TOKENS)
    var configuredIdleMinutes by mutableIntStateOf(DEFAULT_IDLE_TIMEOUT_MINUTES)
    /** When true, requests on-device/offline speech recognition first in [AndroidNativeSttEngine]. */
    var preferOfflineNativeStt by mutableStateOf(true)
    /** When true the Wi-Fi lock stays held for the whole service uptime; otherwise only while the API is active. */
    var keepWifiAwake by mutableStateOf(false)

    // Backward compatibility property for completions
    var activeModel: Model?
        get() = activeChatModel ?: activeAudioModel
        set(value) {
            activeChatModel = value
        }

    private const val PREFS_NAME = "pocket_node_prefs"
    private const val KEY_CHAT_MODEL = "preferred_chat_model"
    private const val KEY_AUDIO_MODEL = "preferred_audio_model"
    private const val KEY_MAX_TOKENS = "api_max_tokens"
    private const val KEY_IDLE_MINUTES = "api_idle_minutes"
    private const val KEY_PREFER_OFFLINE_STT = "prefer_offline_native_stt"
    private const val KEY_KEEP_WIFI_AWAKE = "keep_wifi_awake"

    internal fun clampMaxTokens(tokens: Int): Int =
        tokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)

    internal fun clampIdleMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES)

    fun syncSharedModels() {
        if (activeChatModel == null && activeAudioModel != null) {
            activeChatModel = activeAudioModel
        }
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferredChatModelName = prefs.getString(KEY_CHAT_MODEL, "") ?: ""
        preferredAudioModelName = AndroidNativeSttEngine.ENGINE_MODEL_ID
        configuredMaxTokens = clampMaxTokens(prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS))
        configuredIdleMinutes = clampIdleMinutes(prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES))
        preferOfflineNativeStt = prefs.getBoolean(KEY_PREFER_OFFLINE_STT, true)
        keepWifiAwake = prefs.getBoolean(KEY_KEEP_WIFI_AWAKE, false)
    }

    fun savePreferences(
        context: Context,
        chatModelName: String,
        audioModelName: String = AndroidNativeSttEngine.ENGINE_MODEL_ID,
        maxTokens: Int = configuredMaxTokens,
        idleMinutes: Int = configuredIdleMinutes,
        preferOfflineStt: Boolean = preferOfflineNativeStt,
        keepWifi: Boolean = keepWifiAwake,
    ) {
        val validTokens = clampMaxTokens(maxTokens)
        val validIdleMinutes = clampIdleMinutes(idleMinutes)
        preferredChatModelName = chatModelName
        preferredAudioModelName = audioModelName.ifBlank { AndroidNativeSttEngine.ENGINE_MODEL_ID }
        configuredMaxTokens = validTokens
        configuredIdleMinutes = validIdleMinutes
        preferOfflineNativeStt = preferOfflineStt
        keepWifiAwake = keepWifi
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_MODEL, chatModelName)
            .putString(KEY_AUDIO_MODEL, preferredAudioModelName)
            .putInt(KEY_MAX_TOKENS, validTokens)
            .putInt(KEY_IDLE_MINUTES, validIdleMinutes)
            .putBoolean(KEY_PREFER_OFFLINE_STT, preferOfflineStt)
            .putBoolean(KEY_KEEP_WIFI_AWAKE, keepWifi)
            .apply()
    }
}
