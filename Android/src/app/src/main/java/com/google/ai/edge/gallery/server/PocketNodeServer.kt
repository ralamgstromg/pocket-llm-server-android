package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.server.whisper.WhisperEngine
import com.google.ai.edge.gallery.server.whisper.WhisperTimings
import com.google.ai.edge.gallery.server.whisper.isWhisperStt
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String = "",
    val messages: List<Message> = emptyList(),
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatResponse(val id: String, val model: String = "", val choices: List<Choice>)

@Serializable
data class Choice(val message: Message)

@Serializable
data class TranscriptionResponse(val text: String)

/** Diagnostic header with the Whisper timing breakdown. Not part of the OpenAI-compatible API. */
const val TIMINGS_HEADER = "X-Whisper-Timings"

/** Diagnostic header with the model that served the request. Not part of the OpenAI-compatible API. */
const val MODEL_HEADER = "X-Model-Name"

/** Keeps only printable ASCII so any model name can be sent as a header value. */
internal fun headerSafe(value: String): String = value.filter { it.code in 0x20..0x7E }

private class WhisperOutput(val text: String, val timings: WhisperTimings?)

class PocketNodeServer {
    companion object {
        const val PORT = 8080
    }

    private var server: EmbeddedServer<*, *>? = null
    private val serverDispatcher = Dispatchers.IO.limitedParallelism(2)
    private var serverJob: Job = SupervisorJob()
    private var serverScope: CoroutineScope = CoroutineScope(serverJob + serverDispatcher)

    fun start(context: Context? = null) {
        if (server != null) return
        if (serverJob.isCancelled) {
            serverJob = SupervisorJob()
            serverScope = CoroutineScope(serverJob + serverDispatcher)
        }
        context?.let {
            PocketNodeState.loadPreferences(it)
            
            PocketNodeState.loadPreferences(it)
            PocketNodeLogger.loadPreferences(it)
        
        }
        PocketNodeState.isServerRunning = true
        PocketNodeState.lastStartupError = null

        serverScope.launch {
            try {
                val newServer = embeddedServer(
                    factory = CIO,
                    port = PORT,
                    parentCoroutineContext = serverScope.coroutineContext,
                ) {
                    install(CORS) {
                        anyHost() // Allow connections from n8n or any local network client
                        allowHeader(io.ktor.http.HttpHeaders.ContentType)
                    }
                    install(ContentNegotiation) {
                        json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                    }

                    routing {
                        get("/") {
                            val startMs = System.currentTimeMillis()
                            call.respondText("Pocket Node AI is running!")
                            // Keep health check logs in memory only (context = null) to avoid waking flash storage.
                            PocketNodeLogger.log(
                                method = "GET",
                                route = "/",
                                statusCode = HttpStatusCode.OK.value,
                                durationMs = System.currentTimeMillis() - startMs,
                                detail = "Health check OK",
                                context = null,
                            )
                        }

                        post("/v1/chat/completions") {
                            val startMs = System.currentTimeMillis()
                            var resolvedModelName: String? = null
                            try {
                                val req = call.receive<ChatRequest>()
                                val promptContext = PocketNodeInference.buildChatPromptContext(req.messages)
                                if (promptContext.userPrompt.isBlank()) {
                                    val msg = "Error: No user message provided in 'messages'."
                                    PocketNodeLogger.log(
                                        method = "POST",
                                        route = "/v1/chat/completions",
                                        statusCode = HttpStatusCode.BadRequest.value,
                                        durationMs = System.currentTimeMillis() - startMs,
                                        detail = msg,
                                        context = context,
                                    )
                                    call.respondText(msg, status = HttpStatusCode.BadRequest)
                                    return@post
                                }

                                val (model, err) = resolveAndInitModel(req.model, context, isAudio = false)
                                if (err != null || model == null) {
                                    val errorMsg = err ?: "Model error"
                                    PocketNodeLogger.log(
                                        method = "POST",
                                        route = "/v1/chat/completions",
                                        statusCode = HttpStatusCode.ServiceUnavailable.value,
                                        durationMs = System.currentTimeMillis() - startMs,
                                        model = req.model.ifBlank { PocketNodeState.activeChatModel?.name },
                                        detail = errorMsg,
                                        context = context,
                                    )
                                    call.respondText(errorMsg, status = HttpStatusCode.ServiceUnavailable)
                                    return@post
                                }

                                resolvedModelName = model.name
                                call.response.headers.append(MODEL_HEADER, headerSafe(model.name))
                                val effectiveMaxTokens = req.maxTokens ?: PocketNodeState.configuredMaxTokens
                                val fullResponse = PocketNodeInference.runIsolatedLlmInference(
                                    context = context,
                                    model = model,
                                    prompt = promptContext.userPrompt,
                                    systemInstruction = promptContext.systemInstruction,
                                    temperatureOverride = req.temperature,
                                    maxTokensOverride = req.maxTokens,
                                )
                                val reply = ChatResponse(
                                    id = "chatcmpl-${System.currentTimeMillis()}",
                                    model = model.name,
                                    choices = listOf(Choice(Message("assistant", fullResponse)))
                                )
                                PocketNodeLogger.log(
                                    method = "POST",
                                    route = "/v1/chat/completions",
                                    statusCode = HttpStatusCode.OK.value,
                                    durationMs = System.currentTimeMillis() - startMs,
                                    model = model.name,
                                    detail = "msgs=${req.messages.size}, maxTokens=$effectiveMaxTokens, prompt=${promptContext.userPrompt.length} chars, reply=${fullResponse.take(80)}",
                                    context = context,
                                )
                                call.respond(reply)
                            } catch (t: Throwable) {
                                Log.e("PocketNodeServer", "Unhandled error in /v1/chat/completions", t)
                                PocketNodeLogger.log(
                                    method = "POST",
                                    route = "/v1/chat/completions",
                                    statusCode = HttpStatusCode.InternalServerError.value,
                                    durationMs = System.currentTimeMillis() - startMs,
                                    model = resolvedModelName,
                                    detail = "Exception: ${t.message}",
                                    context = context,
                                )
                                call.respondText("Internal Server Error: ${t.message}", status = HttpStatusCode.InternalServerError)
                            }
                        }

                        post("/v1/audio/transcriptions") {
                            val startMs = System.currentTimeMillis()
                            var resolvedModelName: String? = null
                            try {
                                var rawAudioBytes: ByteArray? = null
                                var promptParam: String? = null
                                var languageParam: String? = null
                                var responseFormatParam: String? = null

                                try {
                                    val multipart = call.receiveMultipart()
                                    multipart.forEachPart { part ->
                                        when (part) {
                                            is PartData.FileItem -> {
                                                rawAudioBytes = part.streamProvider().readBytes()
                                            }
                                            is PartData.FormItem -> {
                                                when (part.name) {
                                                    "prompt" -> promptParam = part.value
                                                    "language" -> languageParam = part.value
                                                    "response_format" -> responseFormatParam = part.value
                                                }
                                            }
                                            else -> {}
                                        }
                                        part.dispose()
                                    }
                                } catch (e: Exception) {
                                    val msg = "Error parsing multipart audio request: ${e.message}"
                                    PocketNodeLogger.log(
                                        method = "POST",
                                        route = "/v1/audio/transcriptions",
                                        statusCode = HttpStatusCode.BadRequest.value,
                                        durationMs = System.currentTimeMillis() - startMs,
                                        detail = msg,
                                        context = context,
                                    )
                                    call.respondText(msg, status = HttpStatusCode.BadRequest)
                                    return@post
                                }

                                if (rawAudioBytes == null || rawAudioBytes!!.isEmpty()) {
                                    val msg = "Error: No audio file uploaded in multipart field 'file'."
                                    PocketNodeLogger.log(
                                        method = "POST",
                                        route = "/v1/audio/transcriptions",
                                        statusCode = HttpStatusCode.BadRequest.value,
                                        durationMs = System.currentTimeMillis() - startMs,
                                        detail = msg,
                                        context = context,
                                    )
                                    call.respondText(msg, status = HttpStatusCode.BadRequest)
                                    return@post
                                }

                                val (audioModel, err) = resolveAndInitModel("", context, isAudio = true)
                                if (err != null || audioModel == null) {
                                    val errorMsg = err ?: "Audio model error"
                                    PocketNodeLogger.log(
                                        method = "POST",
                                        route = "/v1/audio/transcriptions",
                                        statusCode = HttpStatusCode.ServiceUnavailable.value,
                                        durationMs = System.currentTimeMillis() - startMs,
                                        model = PocketNodeState.activeAudioModel?.name,
                                        detail = errorMsg,
                                        context = context,
                                    )
                                    call.respondText(errorMsg, status = HttpStatusCode.ServiceUnavailable)
                                    return@post
                                }

                                resolvedModelName = audioModel.name
                                call.response.headers.append(MODEL_HEADER, headerSafe(audioModel.name))
                                val effectiveLanguage = languageParam?.takeIf { it.isNotBlank() } ?: PocketNodeInference.DEFAULT_LANGUAGE
                                var whisperTimings: WhisperTimings? = null
                                val cleanText = if (audioModel.isWhisperStt) {
                                    val samples = context?.let { AudioDecoderHelper.decodeToMono16kSamples(it, rawAudioBytes!!) }
                                    if (samples == null) {
                                        val msg = "Error: audio no decodificable a 16 kHz mono."
                                        PocketNodeLogger.log(
                                            method = "POST",
                                            route = "/v1/audio/transcriptions",
                                            statusCode = HttpStatusCode.BadRequest.value,
                                            durationMs = System.currentTimeMillis() - startMs,
                                            model = audioModel.name,
                                            detail = msg,
                                            context = context,
                                        )
                                        call.respondText(msg, status = HttpStatusCode.BadRequest)
                                        return@post
                                    }
                                    val output = transcribeWithWhisper(context, audioModel, samples, effectiveLanguage)
                                    whisperTimings = output.timings
                                    output.text
                                } else {
                                    // Decode audio file (MP3, M4A, AAC, OGG, FLAC, WAV) to 16kHz Mono 16-bit PCM WAV
                                    val processedAudioBytes = if (context != null) {
                                        AudioDecoderHelper.decodeToMonoPcmWav(context, rawAudioBytes!!)
                                    } else {
                                        rawAudioBytes!!
                                    }

                                    val prompt = when {
                                        !promptParam.isNullOrBlank() -> PocketNodeInference.sanitizeControlTokens(promptParam!!)
                                        effectiveLanguage.equals("es", ignoreCase = true) || effectiveLanguage.equals("spanish", ignoreCase = true) ->
                                            "Entrega ÚNICAMENTE el texto hablado literal de este audio en español. No incluyas introducciones, etiquetas, comillas ni explicaciones."
                                        else ->
                                            "Output ONLY the verbatim spoken text from this audio in language ${effectiveLanguage}. Do NOT include any intro, explanation, labels, or extra words."
                                    }

                                    val fullResponse = PocketNodeInference.runIsolatedLlmInference(
                                        context = context,
                                        model = audioModel,
                                        prompt = prompt,
                                        audioClips = listOf(processedAudioBytes),
                                    )
                                    sanitizeSpokenText(fullResponse)
                                }
                                whisperTimings?.let { call.response.headers.append(TIMINGS_HEADER, it.toHeaderValue()) }
                                val timingsNote = whisperTimings?.let { " | timings=${it.toHeaderValue()}" }.orEmpty()
                                PocketNodeLogger.log(
                                    method = "POST",
                                    route = "/v1/audio/transcriptions",
                                    statusCode = HttpStatusCode.OK.value,
                                    durationMs = System.currentTimeMillis() - startMs,
                                    model = audioModel.name,
                                    detail = "audio=${rawAudioBytes!!.size}B, lang=$effectiveLanguage, text=${cleanText.ifEmpty { "(vacío)" }.take(60)}$timingsNote",
                                    context = context,
                                )
                                if (responseFormatParam.equals("text", ignoreCase = true)) {
                                    call.respondText(cleanText, ContentType.Text.Plain)
                                } else {
                                    call.respond(TranscriptionResponse(text = cleanText))
                                }
                            } catch (t: Throwable) {
                                Log.e("PocketNodeServer", "Unhandled error in /v1/audio/transcriptions", t)
                                PocketNodeLogger.log(
                                    method = "POST",
                                    route = "/v1/audio/transcriptions",
                                    statusCode = HttpStatusCode.InternalServerError.value,
                                    durationMs = System.currentTimeMillis() - startMs,
                                    model = resolvedModelName,
                                    detail = "Exception: ${t.message}",
                                    context = context,
                                )
                                call.respondText("Internal Server Error: ${t.message}", status = HttpStatusCode.InternalServerError)
                            }
                        }
                    }
                }
                server = newServer.start(wait = false)
                PocketNodeLogger.log(
                    method = "SERVER",
                    route = "start",
                    statusCode = 200,
                    durationMs = 0L,
                    model = PocketNodeState.activeChatModel?.name ?: PocketNodeState.activeAudioModel?.name,
                    detail = "Servidor HTTP iniciado en puerto $PORT (maxTokens=${PocketNodeState.configuredMaxTokens}, idle=${PocketNodeState.configuredIdleMinutes}m)",
                    context = context,
                )
            } catch (e: Exception) {
                Log.e("PocketNodeServer", "Failed to start Ktor HTTP server", e)
                PocketNodeState.isServerRunning = false
                PocketNodeState.lastStartupError = "Error al iniciar servidor (puerto $PORT en uso o error de red): ${e.message}"
                PocketNodeLogger.log(
                    method = "SERVER",
                    route = "start",
                    statusCode = 500,
                    durationMs = 0L,
                    detail = "Error al iniciar servidor: ${e.message}",
                    context = context,
                )
                server = null
            }
        }
        context?.let { preloadActiveModels(it) }
    }

    /** Preloads active models into memory while avoiding dual-model RAM thrashing on memory-constrained devices. */
    private fun preloadActiveModels(context: Context) {
        serverScope.launch {
            val chatModel = PocketNodeState.activeChatModel?.takeUnless { it.isWhisperStt }
            val audioModel = PocketNodeState.activeAudioModel
            val sharedAudio = chatModel != null && audioModel != null && chatModel.name == audioModel.name

            if (chatModel != null) {
                val startMs = System.currentTimeMillis()
                PocketNodeInference.preloadModel(context, chatModel, forAudio = sharedAudio)
                    .onSuccess { backend ->
                        PocketNodeLogger.log(
                            method = "SERVER",
                            route = "preload",
                            statusCode = 200,
                            durationMs = System.currentTimeMillis() - startMs,
                            model = chatModel.name,
                            detail = "Modelo chat precargado en $backend (español, maxTokens=${PocketNodeState.configuredMaxTokens})",
                            context = context,
                        )
                    }
                    .onFailure { e ->
                        Log.w("PocketNodeServer", "Chat model preload skipped: ${e.message}")
                        PocketNodeLogger.log(
                            method = "SERVER",
                            route = "preload",
                            statusCode = 503,
                            durationMs = System.currentTimeMillis() - startMs,
                            model = chatModel.name,
                            detail = "Precarga chat omitida: ${e.message}",
                            context = context,
                        )
                    }
            }

            if (audioModel != null && !sharedAudio) {
                if (chatModel != null && !PocketNodeInference.hasEnoughRamForDualResidency(context)) {
                    PocketNodeLogger.log(
                        method = "SERVER",
                        route = "preload",
                        statusCode = 200,
                        durationMs = 0L,
                        model = audioModel.name,
                        detail = "Modo ahorro RAM/batería: STT listo para carga bajo demanda",
                        context = context,
                    )
                    return@launch
                }
                val startMs = System.currentTimeMillis()
                PocketNodeInference.preloadModel(context, audioModel, forAudio = true)
                    .onSuccess { backend ->
                        PocketNodeLogger.log(
                            method = "SERVER",
                            route = "preload",
                            statusCode = 200,
                            durationMs = System.currentTimeMillis() - startMs,
                            model = audioModel.name,
                            detail = "Modelo audio STT precargado en $backend (español)",
                            context = context,
                        )
                    }
                    .onFailure { e ->
                        Log.w("PocketNodeServer", "Audio model preload skipped: ${e.message}")
                        PocketNodeLogger.log(
                            method = "SERVER",
                            route = "preload",
                            statusCode = 503,
                            durationMs = System.currentTimeMillis() - startMs,
                            model = audioModel.name,
                            detail = "Precarga audio omitida: ${e.message}",
                            context = context,
                        )
                    }
            }
        }
    }

    private suspend fun resolveAndInitModel(
        reqModelName: String,
        context: Context?,
        isAudio: Boolean
    ): Pair<com.google.ai.edge.gallery.data.Model?, String?> {
        PocketNodeState.syncSharedModels()
        var model = if (reqModelName.isNotEmpty() && context != null) {
            PocketNodeModelResolver.findModelByNameOrId(context, reqModelName)
        } else null

        if (model == null) {
            model = if (isAudio) {
                PocketNodeState.activeAudioModel ?: PocketNodeState.activeChatModel ?: PocketNodeState.activeModel
            } else {
                PocketNodeState.activeChatModel ?: PocketNodeState.activeAudioModel ?: PocketNodeState.activeModel
            }
        }
        // Whisper is speech-to-text only; it must never serve as the chat model.
        if (!isAudio && model?.isWhisperStt == true) model = null

        if (model == null && context != null) {
            val allModels = PocketNodeModelResolver.getAllModels(context)
            if (isAudio) {
                val audioCapable = allModels.filter { it.llmSupportAudio || it.bestForTaskIds.contains("llm_ask_audio") || it.isWhisperStt }
                model = audioCapable.find { java.io.File(it.getPath(context)).exists() } ?: audioCapable.firstOrNull() ?: allModels.firstOrNull()
                if (model != null) PocketNodeState.activeAudioModel = model
            } else {
                val chatModels = allModels.filterNot { it.isWhisperStt }
                model = chatModels.find { java.io.File(it.getPath(context)).exists() } ?: chatModels.firstOrNull()
                if (model != null) PocketNodeState.activeChatModel = model
            }
        }

        if (model == null) {
            return Pair(null, "Error: No active model available. Please select a model in Pocket Node Server.")
        }

        if (model.instance == null && context != null) {
            val modelFile = java.io.File(model.getPath(context))
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return Pair(null, "Error: Model file '${model.name}' (${model.downloadFileName}) is not downloaded on device yet. Please download it in the Gallery App first.")
            }

            PocketNodeInference.preloadModel(context, model, forAudio = isAudio).onFailure { e ->
                return Pair(null, "Error initializing model '${model.name}': ${e.message}")
            }
        }

        if (model.instance == null) {
            return Pair(null, "Error: Model '${model.name}' is not initialized in memory.")
        }

        return Pair(model, null)
    }

    private suspend fun transcribeWithWhisper(
        context: Context?,
        model: com.google.ai.edge.gallery.data.Model,
        samples: FloatArray,
        language: String?,
    ): WhisperOutput = PocketNodeInference.modelMutex(model.name).withLock {
        PocketNodeInference.touchActivity(context)
        PocketNodeInference.withInferenceWakeLock(context) {
            withContext(serverDispatcher) {
                val engine = model.instance as WhisperEngine
                WhisperOutput(engine.transcribe(samples, language), engine.lastTimings)
            }
        }
    }

    fun stop() {
        PocketNodeState.isServerRunning = false
        server?.stop(500, 1000, TimeUnit.MILLISECONDS)
        server = null
        serverJob.cancel()
        PocketNodeLogger.log(
            method = "SERVER",
            route = "stop",
            statusCode = 200,
            durationMs = 0L,
            detail = "Servidor HTTP detenido y recursos liberados",
        )
        CoroutineScope( serverDispatcher ).launch {
            PocketNodeInference.releaseAllLoadedModels()
        }
    }

    private fun sanitizeSpokenText(rawInput: String): String {
        var text = rawInput
        // 1. Remove thinking / reasoning blocks <think>...</think>
        text = text.replace(Regex("(?s)<think>.*?</think>"), "")
        text = text.replace(Regex("(?s)<transcript>.*?</transcript>"), "")

        // 2. Remove code blocks ```text ... ```
        text = text.replace(Regex("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```"), "$1")

        // 3. Remove common intro prefixes
        val prefixRegex = Regex(
            "(?i)^\\s*((" +
            "here is the transcription|" +
            "here is the transcribed text|" +
            "transcription|" +
            "transcripci[oó]n|" +
            "el usuario dijo|" +
            "el audio dice|" +
            "the user said|" +
            "the speaker said|" +
            "spoken text|" +
            "texto hablado|" +
            "text" +
            ")\\s*:\\s*)"
        )
        text = text.replace(prefixRegex, "")

        // 4. Strip surrounding quotes and whitespace
        text = text.trim()
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'")) || (text.startsWith("«") && text.endsWith("»"))) {
            text = text.substring(1, text.length - 1).trim()
        }

        return text
    }
}
