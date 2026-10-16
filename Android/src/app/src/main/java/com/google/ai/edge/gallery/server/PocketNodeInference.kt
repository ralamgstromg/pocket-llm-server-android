package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.whisper.WhisperEngine
import com.google.ai.edge.gallery.server.whisper.isWhisperStt
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "PocketNodeInference"

/**
 * Loads and configures models for Pocket Node API serving:
 * - Enables GPU by default when supported by the model (with CPU fallback).
 * - Applies fast sampling/token defaults and disables thinking overhead.
 * - Uses Spanish ("es") as the default language and system instruction.
 */
object PocketNodeInference {

    const val DEFAULT_LANGUAGE = "es"
    const val DEFAULT_SPANISH_SYSTEM_PROMPT =
        "Responde siempre en español de forma clara, directa y concisa, salvo que el usuario solicite explícitamente otro idioma."

    internal const val FAST_MAX_TOKENS = 1024
    internal const val FAST_TOP_K = 16
    internal const val FAST_TOP_P = 0.85f
    internal const val FAST_TEMPERATURE = 0.4f

    /** Returns the preferred accelerator for a model (GPU first when supported, otherwise first available or CPU). */
    internal fun preferredAccelerator(model: Model): Accelerator =
        if (Accelerator.GPU in model.accelerators) Accelerator.GPU
        else model.accelerators.firstOrNull() ?: Accelerator.CPU

    /** Applies low-latency serving settings and GPU preference to [model.configValues]. */
    internal fun applyFastServingConfig(
        model: Model,
        accelerator: Accelerator = preferredAccelerator(model),
    ): Map<String, Any> {
        val currentMax = model.llmMaxToken.takeIf { it > 0 } ?: FAST_MAX_TOKENS
        val updated = model.configValues.toMutableMap().apply {
            put(ConfigKeys.ACCELERATOR.label, accelerator.label)
            put(ConfigKeys.USE_GPU.label, accelerator == Accelerator.GPU)
            put(ConfigKeys.ENABLE_THINKING.label, false)
            put(ConfigKeys.MAX_TOKENS.label, minOf(currentMax, FAST_MAX_TOKENS))
            put(ConfigKeys.TOPK.label, FAST_TOP_K)
            put(ConfigKeys.TOPP.label, FAST_TOP_P)
            put(ConfigKeys.TEMPERATURE.label, FAST_TEMPERATURE)
        }
        model.configValues = updated
        return updated
    }

    /** Preloads either a Whisper TFLite model or an LLM model into memory immediately. */
    suspend fun preloadModel(context: Context, model: Model, forAudio: Boolean): Result<String> {
        val modelFile = File(model.getPath(context))
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return Result.failure(
                IllegalStateException("El archivo del modelo '${model.name}' no está descargado.")
            )
        }
        if (model.isWhisperStt) {
            if (model.instance is WhisperEngine) {
                return Result.success("Whisper (en memoria)")
            }
            return runCatching {
                model.instance = WhisperEngine.load(context, modelFile)
                "XNNPACK/CPU"
            }
        }
        return initializeLlm(
            context = context,
            model = model,
            supportAudio = forAudio && model.llmSupportAudio,
        ).map { it.label }
    }

    /** Returns the backend that loaded the model, or a failure with the error message. */
    suspend fun initializeLlm(
        context: Context,
        model: Model,
        supportAudio: Boolean = model.llmSupportAudio,
    ): Result<Accelerator> {
        if (model.instance != null) {
            val current = Accelerator.entries.firstOrNull {
                it.label == model.configValues[ConfigKeys.ACCELERATOR.label]
            }
            return Result.success(current ?: preferredAccelerator(model))
        }

        val preferred = preferredAccelerator(model)
        val order = listOf(preferred, Accelerator.CPU).distinct()

        var lastError = "Failed to load model into RAM"
        for (accelerator in order) {
            applyFastServingConfig(model, accelerator)
            val error = initializeOnce(context, model, supportAudio = supportAudio)
            if (error == null) {
                Log.i(TAG, "Model '${model.name}' loaded on ${accelerator.label} (Spanish + fast config)")
                return Result.success(accelerator)
            }
            Log.w(TAG, "Model '${model.name}' failed on ${accelerator.label}: $error")
            lastError = error
        }
        return Result.failure(IllegalStateException(lastError))
    }

    /** Resets the LLM conversation before a stateless HTTP request to keep the KV cache empty and Spanish active. */
    fun resetConversationForApi(model: Model, supportAudio: Boolean = false) {
        if (model.instance == null || model.isWhisperStt) return
        LlmChatModelHelper.resetConversation(
            model = model,
            supportImage = false,
            supportAudio = supportAudio && model.llmSupportAudio,
            systemInstruction = Contents.of(DEFAULT_SPANISH_SYSTEM_PROMPT),
        )
    }

    /** Returns null on success, or the error message. */
    private suspend fun initializeOnce(
        context: Context,
        model: Model,
        supportAudio: Boolean,
    ): String? = suspendCoroutine { continuation ->
        LlmChatModelHelper.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = supportAudio,
            systemInstruction = Contents.of(DEFAULT_SPANISH_SYSTEM_PROMPT),
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
