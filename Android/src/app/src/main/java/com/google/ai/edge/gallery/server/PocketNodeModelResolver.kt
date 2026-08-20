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
