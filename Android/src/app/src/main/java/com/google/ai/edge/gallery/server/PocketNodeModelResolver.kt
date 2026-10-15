package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import java.io.File

object PocketNodeModelResolver {
    private const val TAG = "PocketNodeModelResolver"
    private var cachedModels: List<Model>? = null

    fun getAllModels(context: Context): List<Model> {
        cachedModels?.let { return it }

        val allowlist = readAllowlist(context)
        if (allowlist == null) {
            Log.e(TAG, "Could not load model allowlist")
            return emptyList()
        }

        val models = allowlist.models
            .filter { it.disabled != true && it.modelFile.endsWith(".litertlm") }
            .map { allowed ->
                val m = allowed.toModel()
                m.preProcess()
                m
            }

        cachedModels = models
        return models
    }

    fun findModelByNameOrId(context: Context, query: String): Model? =
        ModelMatching.find(
            items = getAllModels(context),
            query = query,
            aliases = PocketNodeState.slots?.aliases().orEmpty(),
            name = { it.name },
            fileName = { it.downloadFileName },
        )

    fun isDownloaded(context: Context, model: Model): Boolean =
        File(model.getPath(context)).let { it.exists() && it.length() > 0 }

    fun supportsAudio(model: Model): Boolean = model.llmSupportAudio || model.bestForTaskIds.contains("llm_ask_audio")

    /** Forces the allowlist to be read again (after an import or a disk edit). */
    fun invalidateCache() {
        cachedModels = null
    }

    /**
     * Saves a user-supplied allowlist as the on-device override and reloads it.
     * Returns null on success, or the reason it was rejected.
     */
    fun importAllowlist(context: Context, json: String): String? {
        val parsed = try {
            Gson().fromJson(json, ModelAllowlist::class.java)
        } catch (e: Exception) {
            return "Invalid JSON: ${e.message}"
        }
        if (parsed?.models.isNullOrEmpty()) return "The model list has no models."
        val dir = context.getExternalFilesDir(null) ?: return "External storage is not available."
        File(dir, "model_allowlist.json").writeText(json)
        invalidateCache()
        return null
    }

    /**
     * Picks the model for [role]. Order: the model named in the request, the role's slot, the chat
     * slot, the model the in-app screens last loaded, then any downloaded model that fits.
     */
    fun pick(context: Context, role: ModelRole, requested: String, needsAudio: Boolean = false, needsImage: Boolean = false): Model? {
        val all = getAllModels(context)
        val fits: (Model) -> Boolean = { (!needsAudio || supportsAudio(it)) && (!needsImage || it.llmSupportImage) }
        findModelByNameOrId(context, requested)?.let { return it }
        val slots = PocketNodeState.slots
        for (name in listOfNotNull(slots?.effective(role), slots?.get(ModelRole.CHAT))) {
            if (name.isEmpty()) continue
            ModelMatching.find(all, name, emptyMap(), { it.name }, { it.downloadFileName })?.takeIf(fits)?.let { return it }
        }
        val loaded = if (needsAudio) PocketNodeState.activeAudioModel else PocketNodeState.activeChatModel
        loaded?.takeIf(fits)?.let { return it }
        val candidates = all.filter(fits)
        return candidates.find { isDownloaded(context, it) } ?: candidates.firstOrNull()
    }

    private fun readAllowlist(context: Context): ModelAllowlist? {
        val gson = Gson()
        // 1. Try local disk allowlist first
        try {
            val diskFile = File(context.getExternalFilesDir(null), "model_allowlist.json")
            if (diskFile.exists()) {
                val json = diskFile.readText()
                val list = gson.fromJson(json, ModelAllowlist::class.java)
                if (list?.models?.isNotEmpty() == true) return list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading allowlist from disk: ${e.message}")
        }

        // 2. Fallback to bundled asset
        try {
            val version = BuildConfig.VERSION_NAME.replace(".", "_")
            val assetPath = "model_allowlists/$version.json"
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return gson.fromJson(json, ModelAllowlist::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading allowlist from assets: ${e.message}")
        }

        return null
    }
}
