package com.google.ai.edge.gallery.server

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.whisper.isWhisperStt

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
    var preferredAudioModelName: String = ""

    // Observable Compose state for reactive UI updates
    var isServerRunning by mutableStateOf(false)
    var lastStartupError by mutableStateOf<String?>(null)
    var configuredMaxTokens by mutableIntStateOf(DEFAULT_MAX_TOKENS)
    var configuredIdleMinutes by mutableIntStateOf(DEFAULT_IDLE_TIMEOUT_MINUTES)

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

    internal fun clampMaxTokens(tokens: Int): Int =
        tokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)

    internal fun clampIdleMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES)

    fun syncSharedModels() {
        if (activeChatModel != null && activeAudioModel != null && activeChatModel?.name == activeAudioModel?.name) {
            activeAudioModel = activeChatModel
        } else if (activeAudioModel == null && activeChatModel?.llmSupportAudio == true) {
            activeAudioModel = activeChatModel
        } else if (activeChatModel == null && activeAudioModel != null && activeAudioModel?.isWhisperStt == false) {
            activeChatModel = activeAudioModel
        }
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferredChatModelName = prefs.getString(KEY_CHAT_MODEL, "") ?: ""
        preferredAudioModelName = prefs.getString(KEY_AUDIO_MODEL, "") ?: ""
        configuredMaxTokens = clampMaxTokens(prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS))
        configuredIdleMinutes = clampIdleMinutes(prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_TIMEOUT_MINUTES))
    }

    fun savePreferences(
        context: Context,
        chatModelName: String,
        audioModelName: String,
        maxTokens: Int = configuredMaxTokens,
        idleMinutes: Int = configuredIdleMinutes,
    ) {
        val validTokens = clampMaxTokens(maxTokens)
        val validIdleMinutes = clampIdleMinutes(idleMinutes)
        preferredChatModelName = chatModelName
        preferredAudioModelName = audioModelName
        configuredMaxTokens = validTokens
        configuredIdleMinutes = validIdleMinutes
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_MODEL, chatModelName)
            .putString(KEY_AUDIO_MODEL, audioModelName)
            .putInt(KEY_MAX_TOKENS, validTokens)
            .putInt(KEY_IDLE_MINUTES, validIdleMinutes)
            .apply()
    }
}
