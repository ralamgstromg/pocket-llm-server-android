package com.google.ai.edge.gallery.server

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PocketNodeInference"

/** Structured prompt context extracted from OpenAI-compatible [Message] list. */
internal data class ChatPromptContext(
    val systemInstruction: String,
    val userPrompt: String,
)

/**
 * Loads, isolates, and manages LLM models for Pocket Node API serving:
 * - Enables GPU by default when supported by the model (with CPU fallback).
 * - Applies fast sampling/token defaults (8000 tokens default, configurable via UI or per-request `max_tokens`).
 * - Uses Spanish ("es") as the default language and system instruction.
 * - Keeps the LiteRT-LM [com.google.ai.edge.litertlm.Engine] warm with zero KV-cache buffers in idle (`closeIdleConversation`),
 *   creating a disposable [com.google.ai.edge.litertlm.Conversation] (< 5 ms) only while a request is executing.
 * - Saves battery via on-demand WakeLocks, memory pressure protection, and configurable idle hibernation (3m default).
 */
object PocketNodeInference {

    const val DEFAULT_LANGUAGE = "es"
    const val DEFAULT_SPANISH_SYSTEM_PROMPT =
        "Responde siempre en español de forma clara, directa y concisa, salvo que el usuario solicite explícitamente otro idioma. " +
            "Atiende únicamente la solicitud actual del usuario sin mezclar contextos previos ni emitir etiquetas de rol."

    internal const val FAST_MAX_TOKENS = PocketNodeState.DEFAULT_MAX_TOKENS
    internal const val FAST_TOP_K = 16
    internal const val FAST_TOP_P = 0.85f
    internal const val FAST_TEMPERATURE = 0.4f

    /** Minimum free system RAM (1.2 GB) required to keep a second separate model simultaneously resident. */
    private const val MIN_FREE_RAM_FOR_DUAL_RESIDENCY_BYTES = 1_200L * 1024L * 1024L
    private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L

    private val CONTROL_TOKEN_REGEX = Regex(
        "(?i)(<start_of_turn>\\s*(user|model|system|developer)?|<end_of_turn>|<\\|im_start\\|>\\s*(user|assistant|system|developer)?|<\\|im_end\\|>|<\\|endoftext\\|>|<bos>|<eos>|<ctrl\\d+>)"
    )

    private val modelMutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val lastActivityMs = AtomicLong(0L)
    @Volatile private var idleWatchdogJob: Job? = null

    /** Global per-model mutex shared by UI preload, server startup preload, and HTTP requests. */
    fun modelMutex(modelName: String): Mutex =
        modelMutexes.getOrPut(modelName) { Mutex() }

    /** Current idle unload timeout in milliseconds based on [PocketNodeState.configuredIdleMinutes]. */
    internal fun currentIdleTimeoutMs(): Long =
        PocketNodeState.clampIdleMinutes(PocketNodeState.configuredIdleMinutes) * 60_000L

    /** Strips raw model template/control tokens so untrusted text cannot corrupt turn boundaries. */
    internal fun sanitizeControlTokens(text: String): String =
        text.replace(CONTROL_TOKEN_REGEX, "").trim()

    /** Cleans generated output from stray control tokens or reasoning tags. */
    internal fun sanitizeModelOutput(rawOutput: String): String =
        rawOutput
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(CONTROL_TOKEN_REGEX, "")
            .trim()

    /**
     * Builds a clean [ChatPromptContext] from an OpenAI `messages` list:
     * - Merges any `system` / `developer` messages into [ChatPromptContext.systemInstruction] alongside [DEFAULT_SPANISH_SYSTEM_PROMPT].
     * - Strips control tags from all messages.
     * - Separates prior conversation turns (`<historial_conversacion>`) from the active user request (`<mensaje_actual_usuario>`)
     *   when multi-turn context is provided, preventing turn confusion.
     */
    internal fun buildChatPromptContext(messages: List<Message>): ChatPromptContext {
        val systemParts = mutableListOf(DEFAULT_SPANISH_SYSTEM_PROMPT)
        val conversationTurns = mutableListOf<Message>()

        for (msg in messages) {
            val cleanContent = sanitizeControlTokens(msg.content)
            if (cleanContent.isEmpty()) continue
            val role = msg.role.trim().lowercase()
            if (role == "system" || role == "developer") {
                systemParts += cleanContent
            } else {
                conversationTurns += Message(role = role, content = cleanContent)
            }
        }

        val effectiveSystem = systemParts.distinct().joinToString("\n\n")
        if (conversationTurns.isEmpty()) {
            return ChatPromptContext(systemInstruction = effectiveSystem, userPrompt = "")
        }

        if (conversationTurns.size == 1) {
            return ChatPromptContext(
                systemInstruction = effectiveSystem,
                userPrompt = conversationTurns.first().content,
            )
        }

        val historyTurns = conversationTurns.dropLast(1)
        val currentTurn = conversationTurns.last()
        val formattedHistory = historyTurns.joinToString("\n") { turn ->
            val label = when (turn.role) {
                "assistant", "model" -> "Asistente"
                else -> "Usuario"
            }
            "[$label]: ${turn.content}"
        }

        val structuredPrompt = buildString {
            appendLine("<historial_conversacion>")
            appendLine(formattedHistory)
            appendLine("</historial_conversacion>")
            appendLine()
            appendLine("<mensaje_actual_usuario>")
            appendLine(currentTurn.content)
            append("</mensaje_actual_usuario>")
        }

        return ChatPromptContext(
            systemInstruction = effectiveSystem,
            userPrompt = structuredPrompt,
        )
    }

    /** Returns the preferred accelerator for a model (GPU first when supported, otherwise first available or CPU). */
    internal fun preferredAccelerator(model: Model): Accelerator =
        if (Accelerator.GPU in model.accelerators) Accelerator.GPU
        else model.accelerators.firstOrNull() ?: Accelerator.CPU

    /**
     * Applies serving settings and GPU preference to [model.configValues].
     * Uses [maxTokensOverride] if provided, otherwise [PocketNodeState.configuredMaxTokens] (default 8000),
     * without clamping down to the catalog's default `llmMaxToken` (1024/4096) so 8000-token requests succeed.
     */
    internal fun applyFastServingConfig(
        model: Model,
        accelerator: Accelerator = preferredAccelerator(model),
        maxTokensOverride: Int? = null,
    ): Map<String, Any> {
        val requestedTokens = maxTokensOverride?.takeIf { it > 0 } ?: PocketNodeState.configuredMaxTokens
        val targetMaxTokens = PocketNodeState.clampMaxTokens(requestedTokens)
        val updated = model.configValues.toMutableMap().apply {
            put(ConfigKeys.ACCELERATOR.label, accelerator.label)
            put(ConfigKeys.USE_GPU.label, accelerator == Accelerator.GPU)
            put(ConfigKeys.ENABLE_THINKING.label, false)
            put(ConfigKeys.MAX_TOKENS.label, targetMaxTokens)
            put(ConfigKeys.TOPK.label, FAST_TOP_K)
            put(ConfigKeys.TOPP.label, FAST_TOP_P)
            put(ConfigKeys.TEMPERATURE.label, FAST_TEMPERATURE)
        }
        model.configValues = updated
        return updated
    }

    /** Checks if the device has enough free RAM to keep two separate models loaded without triggering zram/kswapd0 thrashing. */
    internal fun hasEnoughRamForDualResidency(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return !memInfo.lowMemory && memInfo.availMem >= MIN_FREE_RAM_FOR_DUAL_RESIDENCY_BYTES
    }

    /** Frees any other loaded model before loading [targetModel] if system RAM is tight, preventing background swap drain. */
    private fun guardMemoryBeforeLoad(context: Context, targetModel: Model) {
        if (hasEnoughRamForDualResidency(context)) return
        val otherModels = listOfNotNull(
            PocketNodeState.activeChatModel,
            PocketNodeState.activeAudioModel,
            PocketNodeState.activeModel,
        ).distinctBy { it.name }.filter { it.name != targetModel.name && it.instance != null }

        for (other in otherModels) {
            LlmChatModelHelper.cleanUp(other, onDone = {})
            Log.i(TAG, "Evicted '${other.name}' to prevent low-RAM zram thrashing before loading '${targetModel.name}'")
        }
    }

    /** Preloads an LLM model into memory immediately (thread-safe). */
    suspend fun preloadModel(
        context: Context,
        model: Model,
        forAudio: Boolean = false,
        maxTokensOverride: Int? = null,
    ): Result<String> = modelMutex(model.name).withLock {
        preloadModelLocked(context, model, forAudio, maxTokensOverride)
    }

    internal suspend fun preloadModelLocked(
        context: Context,
        model: Model,
        forAudio: Boolean = false,
        maxTokensOverride: Int? = null,
    ): Result<String> {
        touchActivity(context)
        val modelFile = File(model.getPath(context))
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return Result.failure(
                IllegalStateException("El archivo del modelo '${model.name}' no está descargado.")
            )
        }
        guardMemoryBeforeLoad(context, model)
        return initializeLlmLocked(
            context = context,
            model = model,
            supportAudio = forAudio && model.llmSupportAudio,
            maxTokensOverride = maxTokensOverride,
        ).map { it.label }
    }

    /** Returns the backend that loaded the model, or a failure with the error message. */
    suspend fun initializeLlm(
        context: Context,
        model: Model,
        supportAudio: Boolean = model.llmSupportAudio,
        maxTokensOverride: Int? = null,
    ): Result<Accelerator> = modelMutex(model.name).withLock {
        initializeLlmLocked(context, model, supportAudio, maxTokensOverride)
    }

    internal suspend fun initializeLlmLocked(
        context: Context,
        model: Model,
        supportAudio: Boolean = model.llmSupportAudio,
        maxTokensOverride: Int? = null,
    ): Result<Accelerator> {
        touchActivity(context)
        val desiredMaxTokens = PocketNodeState.clampMaxTokens(
            maxTokensOverride?.takeIf { it > 0 } ?: PocketNodeState.configuredMaxTokens
        )
        val currentMaxTokens = model.getIntConfigValue(
            key = ConfigKeys.MAX_TOKENS,
            defaultValue = PocketNodeState.DEFAULT_MAX_TOKENS,
        )

        if (model.instance != null && currentMaxTokens == desiredMaxTokens) {
            closeIdleConversation(model)
            val current = Accelerator.entries.firstOrNull {
                it.label == model.configValues[ConfigKeys.ACCELERATOR.label]
            }
            return Result.success(current ?: preferredAccelerator(model))
        }

        if (model.instance != null && currentMaxTokens != desiredMaxTokens) {
            Log.i(TAG, "Reinitializing '${model.name}' to apply new maxTokens=$desiredMaxTokens (was $currentMaxTokens)")
            LlmChatModelHelper.cleanUp(model, onDone = {})
        }

        val preferred = preferredAccelerator(model)
        val order = listOf(preferred, Accelerator.CPU).distinct()

        var lastError = "Failed to load model into RAM"
        for (accelerator in order) {
            applyFastServingConfig(model, accelerator, maxTokensOverride = desiredMaxTokens)
            val error = initializeOnce(context, model, supportAudio = supportAudio)
            if (error == null) {
                // Immediately release the initial Conversation KV-cache while keeping the Engine warm!
                closeIdleConversation(model)
                Log.i(TAG, "Model '${model.name}' loaded on ${accelerator.label} (maxTokens=$desiredMaxTokens, zero idle KV-cache)")
                return Result.success(accelerator)
            }
            Log.w(TAG, "Model '${model.name}' failed on ${accelerator.label}: $error")
            lastError = error
        }
        return Result.failure(IllegalStateException(lastError))
    }

    /** Closes any open [com.google.ai.edge.litertlm.Conversation] while keeping [LlmModelInstance.engine] warm in RAM. */
    private fun closeIdleConversation(model: Model) {
        (model.instance as? LlmModelInstance)?.closeIdleConversation()
    }

    /**
     * Executes an isolated, stateless LLM inference in its own disposable [com.google.ai.edge.litertlm.Conversation]:
     * - Acquires a temporary CPU WakeLock only for the duration of the inference.
     * - Creates a dedicated Conversation (< 5 ms) with [systemInstruction] so requests never share KV cache.
     * - Cancels and closes the Conversation in `finally` (`closeIdleConversation`), holding zero KV-cache when idle.
     */
    suspend fun runIsolatedLlmInference(
        context: Context?,
        model: Model,
        prompt: String,
        systemInstruction: String = DEFAULT_SPANISH_SYSTEM_PROMPT,
        audioClips: List<ByteArray> = emptyList(),
        temperatureOverride: Float? = null,
        maxTokensOverride: Int? = null,
    ): String = modelMutex(model.name).withLock {
        touchActivity(context)
        withInferenceWakeLock(context) {
            val targetMaxTokens = PocketNodeState.clampMaxTokens(
                maxTokensOverride?.takeIf { it > 0 } ?: PocketNodeState.configuredMaxTokens
            )
            val currentMaxTokens = model.getIntConfigValue(
                key = ConfigKeys.MAX_TOKENS,
                defaultValue = PocketNodeState.DEFAULT_MAX_TOKENS,
            )
            if (context != null && (model.instance == null || currentMaxTokens != targetMaxTokens)) {
                guardMemoryBeforeLoad(context, model)
                initializeLlmLocked(
                    context = context,
                    model = model,
                    supportAudio = audioClips.isNotEmpty() && model.llmSupportAudio,
                    maxTokensOverride = targetMaxTokens,
                ).onFailure { e ->
                    return@withInferenceWakeLock "[Error: ${e.message}]"
                }
            }

            val instance = model.instance as? LlmModelInstance
                ?: return@withInferenceWakeLock "[Error: LlmModelInstance is not initialized.]"

            val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
            val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
            val temperature = temperatureOverride
                ?: model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
            val accelerator = model.getStringConfigValue(
                key = ConfigKeys.ACCELERATOR,
                defaultValue = Accelerator.GPU.label,
            )

            closeIdleConversation(model)

            val requestConversation = instance.engine.createConversation(
                ConversationConfig(
                    samplerConfig = if (accelerator == Accelerator.NPU.label) {
                        null
                    } else {
                        SamplerConfig(
                            topK = topK,
                            topP = topP.toDouble(),
                            temperature = temperature.toDouble(),
                        )
                    },
                    systemInstruction = Contents.of(systemInstruction),
                    tools = emptyList(),
                )
            )
            instance.conversation = requestConversation

            val contents = mutableListOf<Content>()
            for (audioClip in audioClips) {
                contents.add(Content.AudioBytes(audioClip))
            }
            val cleanPrompt = sanitizeControlTokens(prompt)
            if (cleanPrompt.isNotEmpty()) {
                contents.add(Content.Text(cleanPrompt))
            }

            val responseBuilder = StringBuilder()
            val isFinished = AtomicBoolean(false)

            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation {
                        if (isFinished.compareAndSet(false, true)) {
                            runCatching { requestConversation.cancelProcess() }
                        }
                    }

                    requestConversation.sendMessageAsync(
                        Contents.of(contents),
                        object : MessageCallback {
                            override fun onMessage(message: LiteRtMessage) {
                                if (isFinished.get()) return
                                val chunk = message.toString()
                                if (!chunk.startsWith("<ctrl")) {
                                    responseBuilder.append(chunk)
                                }
                            }

                            override fun onDone() {
                                if (isFinished.compareAndSet(false, true) && continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                if (isFinished.compareAndSet(false, true) && continuation.isActive) {
                                    if (throwable !is CancellationException) {
                                        Log.e(TAG, "Inference error on ${model.name}", throwable)
                                        responseBuilder.append("\n[Error: ${throwable.message}]")
                                    }
                                    continuation.resume(Unit)
                                }
                            }
                        },
                        emptyMap(),
                    )
                }
            } finally {
                instance.closeIdleConversation()
            }

            sanitizeModelOutput(responseBuilder.toString())
        }
    }

    /** Records API activity and resets the idle model unload watchdog to save battery when inactive. */
    fun touchActivity(context: Context? = null) {
        lastActivityMs.set(System.currentTimeMillis())
        scheduleIdleWatchdog(context?.applicationContext)
    }

    /** Acquires a temporary CPU WakeLock strictly during active inference so idle server time uses near-zero battery. */
    internal suspend fun <T> withInferenceWakeLock(context: Context?, block: suspend () -> T): T {
        val powerManager = context?.applicationContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PocketNode::InferenceWakeLock",
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKELOCK_TIMEOUT_MS) }
        }
        return try {
            block()
        } finally {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    runCatching { wl.release() }
                }
            }
        }
    }

    private fun scheduleIdleWatchdog(appContext: Context?) {
        idleWatchdogJob?.cancel()
        val timeoutMs = currentIdleTimeoutMs()
        idleWatchdogJob = backgroundScope.launch {
            delay(timeoutMs)
            val elapsed = System.currentTimeMillis() - lastActivityMs.get()
            if (elapsed >= timeoutMs && PocketNodeState.isServerRunning) {
                val released = releaseAllLoadedModels()
                if (released.isNotEmpty()) {
                    val mins = PocketNodeState.configuredIdleMinutes
                    Log.i(TAG, "Hibernated idle models after ${mins}m to save battery: ${released.joinToString()}")
                    PocketNodeLogger.log(
                        method = "SERVER",
                        route = "hibernate",
                        statusCode = 200,
                        durationMs = 0L,
                        model = released.joinToString(", "),
                        detail = "Modelos en hibernación tras ${mins}m sin uso para ahorrar batería (recarga rápida con caché GPU)",
                        context = appContext,
                    )
                }
            }
        }
    }

    /** Releases all currently loaded LLM models from RAM and GPU to conserve battery. */
    suspend fun releaseAllLoadedModels(): List<String> {
        idleWatchdogJob?.cancel()
        val candidates = listOfNotNull(
            PocketNodeState.activeChatModel,
            PocketNodeState.activeAudioModel,
            PocketNodeState.activeModel,
        ).distinctBy { it.name }

        val released = mutableListOf<String>()
        for (model in candidates) {
            modelMutex(model.name).withLock {
                if (model.instance != null) {
                    LlmChatModelHelper.cleanUp(model, onDone = {})
                    released += model.name
                }
            }
        }
        return released
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
