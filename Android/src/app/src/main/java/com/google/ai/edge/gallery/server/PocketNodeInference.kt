package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "PocketNodeInference"

/** Loads LLMs for serving. Uses GPU when the model supports it and falls back to CPU if that fails. */
object PocketNodeInference {

    /** Returns the backend that loaded the model, or a failure with the error message. */
    suspend fun initializeLlm(context: Context, model: Model): Result<Accelerator> {
        if (model.instance != null) {
            val current = Accelerator.entries.firstOrNull { it.label == model.configValues[ConfigKeys.ACCELERATOR.label] }
            return Result.success(current ?: Accelerator.CPU)
        }

        val preferred = if (Accelerator.GPU in model.accelerators) Accelerator.GPU
        else model.accelerators.firstOrNull() ?: Accelerator.CPU
        val order = listOf(preferred, Accelerator.CPU).distinct()

        var lastError = "Failed to load model into RAM"
        for (accelerator in order) {
            model.configValues = model.configValues + (ConfigKeys.ACCELERATOR.label to accelerator.label)
            val error = initializeOnce(context, model)
            if (error == null) {
                Log.i(TAG, "Model '${model.name}' loaded on ${accelerator.label}")
                return Result.success(accelerator)
            }
            Log.w(TAG, "Model '${model.name}' failed on ${accelerator.label}: $error")
            lastError = error
        }
        return Result.failure(IllegalStateException(lastError))
    }

    /** Returns null on success, or the error message. */
    private suspend fun initializeOnce(context: Context, model: Model): String? = suspendCoroutine { continuation ->
        LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
            onDone = { errorMsg ->
                continuation.resume(
                    when {
                        errorMsg.isNotEmpty() -> errorMsg
                        model.instance == null -> "Failed to load model into RAM"
                        else -> null
                    }
                )
            },
        )
    }
}
