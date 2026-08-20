package com.google.ai.edge.gallery.server

import android.content.Context
import com.google.ai.edge.gallery.data.Model

object PocketNodeState {
    var activeChatModel: Model? = null
    var activeAudioModel: Model? = null

    var preferredChatModelName: String = ""
    var preferredAudioModelName: String = ""

    // Backward compatibility property for completions
    var activeModel: Model?
        get() = activeChatModel ?: activeAudioModel
        set(value) {
            activeChatModel = value
        }

    var isServerRunning: Boolean = false

    private const val PREFS_NAME = "pocket_node_prefs"
    private const val KEY_CHAT_MODEL = "preferred_chat_model"
    private const val KEY_AUDIO_MODEL = "preferred_audio_model"

    fun syncSharedModels() {
        if (activeChatModel != null && activeAudioModel != null && activeChatModel?.name == activeAudioModel?.name) {
            activeAudioModel = activeChatModel
        } else if (activeAudioModel == null && activeChatModel?.llmSupportAudio == true) {
            activeAudioModel = activeChatModel
        } else if (activeChatModel == null && activeAudioModel != null) {
            activeChatModel = activeAudioModel
        }
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferredChatModelName = prefs.getString(KEY_CHAT_MODEL, "") ?: ""
        preferredAudioModelName = prefs.getString(KEY_AUDIO_MODEL, "") ?: ""
    }

    fun savePreferences(context: Context, chatModelName: String, audioModelName: String) {
        preferredChatModelName = chatModelName
        preferredAudioModelName = audioModelName
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHAT_MODEL, chatModelName)
            .putString(KEY_AUDIO_MODEL, audioModelName)
            .apply()
    }
}


