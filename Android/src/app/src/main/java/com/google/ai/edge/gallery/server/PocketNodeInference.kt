package com.google.ai.edge.gallery.server

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.whisper.WhisperEngine
import com.google.ai.edge.gallery.server.whisper.isWhisperStt
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
 * Loads, isolates, and manages models for Pocket Node API serving:
 * - Enables GPU by default when supported by the model (with CPU fallback).
 * - Applies fast sampling/token defaults and disables thinking overhead.
 * - Uses Spanish ("es") as the default language and system instruction.
 * - Isolates each HTTP inference into its own disposable LiteRT-LM [com.google.ai.edge.litertlm.Conversation]
 *   guarded by a global per-model [Mutex] so requests never mix context or tokens.
 * - Saves battery via on-demand WakeLocks during active inference and automatic idle model hibernation.
 */
object PocketNodeInference {

    const val DEFAULT_LANGUAGE = "es"
    const val DEFAULT_SPANISH_SYSTEM_PROMPT =
        "Responde siempre en español de forma clara, directa y concisa, salvo que el usuario solicite explícitamente otro idioma. " +
            "Atiende únicamente la solicitud actual del usuario sin mezclar contextos previos ni emitir etiquetas de rol."

    internal const val FAST_MAX_TOKENS = 512
    internal const val FAST_TOP_K = 16
    internal const val FAST_TOP_P = 0.85f
    internal const val FAST_TEMPERATURE = 0.4f

    /** Unload models from RAM/GPU after 10 minutes of zero API activity to extend battery life. */
    internal const val IDLE_UNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
    private const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L

    private val CONTROL_TOKEN_REGEX = Regex(
        "(?i)(<start_of_turn>\\s*(user|model|system|developer)?|<end_of_turn>|<\\|im_start\\|>\\s*(user|assistant|system|developer)?|<\\|im_end\\|>|<\\|endoftext\\|>|<bos>|<eos>|<ctrl\\d+>)"
    )

    private val modelMutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastActivityMs = AtomicLong(0L)
    @Volatile private var idleWatchdogJob: Job? = null

    /** Global per-model mutex shared by UI preload, server startup preload, and HTTP requests. */
    fun modelMutex(modelName: String): Mutex =
        modelMutexes.getOrPut(modelName) { Mutex() }

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

    /** Applies low-latency, battery-conscious serving settings and GPU preference to [model.configValues]. */
    internal fun applyFastServingConfig(
        model: Model,
        accelerator: Accelerator = preferredAccelerator(model),
        maxTokensOverride: Int? = null,
    ): Map<String, Any> {
        val modelCap = model.llmMaxToken.takeIf { it > 0 } ?: FAST_MAX_TOKENS
        val targetMaxTokens = (maxTokensOverride?.takeIf { it > 0 } ?: FAST_MAX_TOKENS).coerceAtMost(modelCap)
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

    /** Preloads either a Whisper TFLite model or an LLM model into memory immediately (thread-safe). */
    suspend fun preloadModel(context: Context, model: Model, forAudio: Boolean): Result<String> =
        modelMutex(model.name).withLock {
            preloadModelLocked(context, model, forAudio)
        }

    internal suspend fun preloadModelLocked(context: Context, model: Model, forAudio: Boolean): Result<String> {
        touchActivity(context)
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
        return initializeLlmLocked(
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
    ): Result<Accelerator> = modelMutex(model.name).withLock {
        initializeLlmLocked(context, model, supportAudio)
    }

    internal suspend fun initializeLlmLocked(
        context: Context,
        model: Model,
        supportAudio: Boolean = model.llmSupportAudio,
    ): Result<Accelerator> {
        touchActivity(context)
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

    /**
     * Executes an isolated, stateless LLM inference in its own disposable [com.google.ai.edge.litertlm.Conversation]:
     * - Acquires a temporary CPU WakeLock only for the duration of the inference.
     * - Creates a dedicated Conversation with [systemInstruction] so requests never share KV cache.
     * - Cancels and closes the Conversation in `finally` (including on client disconnect/timeout).
     */
    suspend fun runIsolatedLlmInference(
        context: Context?,
        model: Model,
        prompt: String,
        systemInstruction: String = DEFAULT_SPANISH_SYSTEM_PROMPT,
        audioClips: List<ByteArray> = emptyList(),
        temperatureOverride: Float? = null,
    ): String = modelMutex(model.name).withLock {
        touchActivity(context)
        withInferenceWakeLock(context) {
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

            // Close any previous default conversation before creating an isolated one for this request.
            runCatching { instance.conversation.cancelProcess() }
            runCatching { instance.conversation.close() }

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
                runCatching { requestConversation.cancelProcess() }
                runCatching { requestConversation.close() }
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
        idleWatchdogJob = backgroundScope.launch {
            delay(IDLE_UNLOAD_TIMEOUT_MS)
            val elapsed = System.currentTimeMillis() - lastActivityMs.get()
            if (elapsed >= IDLE_UNLOAD_TIMEOUT_MS && PocketNodeState.isServerRunning) {
                val released = releaseAllLoadedModels()
                if (released.isNotEmpty()) {
                    Log.i(TAG, "Hibernated idle models to save battery: ${released.joinToString()}")
                    PocketNodeLogger.log(
                        method = "SERVER",
                        route = "hibernate",
                        statusCode = 200,
                        durationMs = 0L,
                        model = released.joinToString(", "),
                        detail = "Modelos liberados tras 10m de inactividad para ahorrar batería (se recargarán al recibir petición)",
                        context = appContext,
                    )
                }
            }
        }
    }

    /** Releases all currently loaded Chat/Audio models from RAM and GPU to conserve battery. */
    suspend fun releaseAllLoadedModels(): List<String> {
        val candidates = listOfNotNull(
            PocketNodeState.activeChatModel,
            PocketNodeState.activeAudioModel,
            PocketNodeState.activeModel,
        ).distinctBy { it.name }

        val released = mutableListOf<String>()
        for (model in candidates) {
            modelMutex(model.name).withLock {
                if (model.instance != null) {
                    if (model.isWhisperStt) {
                        runCatching { (model.instance as? WhisperEngine)?.close() }
                        model.instance = null
                    } else {
                        LlmChatModelHelper.cleanUp(model, onDone = {})
                    }
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
