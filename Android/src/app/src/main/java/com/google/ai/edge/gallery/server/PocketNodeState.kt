package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model

object PocketNodeState {
    var activeChatModel: Model? = null
    var activeAudioModel: Model? = null

    // Backward compatibility property for completions
    var activeModel: Model?
        get() = activeChatModel ?: activeAudioModel
        set(value) {
            activeChatModel = value
        }

    var isServerRunning: Boolean = false
}

