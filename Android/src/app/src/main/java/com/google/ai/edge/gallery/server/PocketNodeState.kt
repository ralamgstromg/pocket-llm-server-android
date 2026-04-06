package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model

object PocketNodeState {
    var activeModel: Model? = null
    var isServerRunning: Boolean = false
}
