package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.DefaultConfig
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
            .filter { it.disabled != true }
            .map { allowed ->
                val m = allowed.toModel()
                m.preProcess()
                m
            }

        cachedModels = models
        return models
    }

    fun findModelByNameOrId(context: Context, query: String): Model? {
        if (query.isBlank()) return null
        val models = getAllModels(context)
        return models.find { m ->
            m.name.equals(query, ignoreCase = true) ||
            m.downloadFileName.equals(query, ignoreCase = true) ||
            query.contains(m.name, ignoreCase = true)
        }
    }

    private fun readAllowlist(context: Context): ModelAllowlist? {
        val disk = readDiskAllowlist(context)
        val asset = readAssetAllowlist(context)
        return when {
            disk == null -> asset
            asset == null -> disk
            else -> mergeAllowlists(primary = disk, fallback = asset)
        }
    }

    /**
     * The disk copy can be an older allowlist written by the Gallery, so models that only exist in
     * the bundled asset (for example Whisper) are added to it instead of being hidden, and models
     * whose bundled asset enables GPU upgrade stale CPU-only disk entries to include GPU.
     */
    internal fun mergeAllowlists(primary: ModelAllowlist, fallback: ModelAllowlist): ModelAllowlist {
        val fallbackByName = fallback.models.associateBy { it.name }
        val updatedPrimary = primary.models.onEach { diskModel ->
            val assetModel = fallbackByName[diskModel.name]
            val assetAccels = assetModel?.defaultConfig?.accelerators
            val diskAccels = diskModel.defaultConfig?.accelerators
            if (assetAccels != null && assetAccels.contains("gpu", ignoreCase = true) &&
                (diskAccels == null || !diskAccels.contains("gpu", ignoreCase = true))
            ) {
                if (diskModel.defaultConfig == null) {
                    diskModel.defaultConfig = DefaultConfig(null, null, null, assetAccels, null, null, null)
                } else {
                    diskModel.defaultConfig.accelerators = assetAccels
                }
            }
        }
        val names = updatedPrimary.map { it.name }.toSet()
        return ModelAllowlist(models = updatedPrimary + fallback.models.filter { it.name !in names })
    }

    private fun readDiskAllowlist(context: Context): ModelAllowlist? {
        try {
            val diskFile = File(context.getExternalFilesDir(null), "model_allowlist.json")
            if (diskFile.exists()) {
                val list = Gson().fromJson(diskFile.readText(), ModelAllowlist::class.java)
                if (list?.models?.isNotEmpty() == true) return list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading allowlist from disk: ${e.message}")
        }
        return null
    }

    private fun readAssetAllowlist(context: Context): ModelAllowlist? {
        try {
            val version = BuildConfig.VERSION_NAME.replace(".", "_")
            val assetPath = "model_allowlists/$version.json"
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return Gson().fromJson(json, ModelAllowlist::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading allowlist from assets: ${e.message}")
        }
        return null
    }
}
