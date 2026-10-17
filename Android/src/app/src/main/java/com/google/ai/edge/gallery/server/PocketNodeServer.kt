package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatRequest(
    val model: String = "",
    val messages: List<Message> = emptyList(),
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<ToolDefinition>? = null,
    val tool_choice: JsonElement? = null,
)

/** Request message. `content` is null for assistant turns that only carry `tool_calls`. */
@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
)

@Serializable
data class ToolDefinition(val type: String = "function", val function: FunctionDefinition)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

/** OpenAI tool call. `arguments` is a JSON string, as in the OpenAI API. No defaults: always serialized. */
@Serializable
data class ToolCall(val id: String, val type: String, val function: ToolCallFunction)

@Serializable
data class ToolCallFunction(val name: String, val arguments: String)

/** Response message. Both fields are always serialized, so `content` is an explicit `null` for tool calls. */
@Serializable
data class ResponseMessage(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCall>?,
)

@Serializable
data class ChatResponse(val id: String, val model: String = "", val choices: List<Choice>)

@Serializable
data class Choice(val index: Int, val message: ResponseMessage, val finish_reason: String)

@Serializable
data class TranscriptionResponse(val text: String)

@Serializable
data class VerboseTranscriptionResponse(
    val task: String = "transcribe",
    val language: String,
    val duration: Double,
    val text: String,
    val segments: List<VerboseSegment>,
)

@Serializable
data class VerboseSegment(val id: Int, val start: Double, val end: Double, val text: String)

@Serializable
data class OpenAiErrorBody(val message: String, val type: String)

@Serializable
data class OpenAiErrorResponse(val error: OpenAiErrorBody)

@Serializable
data class SpeechRequest(
    val model: String = "",
    val input: String = "",
    val voice: String? = null,
    @SerialName("response_format") val responseFormat: String? = null,
    val speed: Double? = null,
)

/** Diagnostic header with the STT timing breakdown. Not part of the OpenAI-compatible API. */
const val TIMINGS_HEADER = "X-STT-Timings"

/** Diagnostic header with the model/service that served the request. Not part of the OpenAI-compatible API. */
const val MODEL_HEADER = "X-Model-Name"

private const val TRANSCRIPTION_ROUTE = "/v1/audio/transcriptions"
private val SUPPORTED_TRANSCRIPTION_FORMATS = setOf("json", "text", "srt", "verbose_json", "vtt")
private const val SPEECH_ROUTE = "/v1/audio/speech"
private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024
private const val MAX_JSON_BYTES = 1L * 1024 * 1024
private const val MAX_SPEECH_BODY_BYTES = 64L * 1024

/** Keeps only printable ASCII so any model name can be sent as a header value. */
internal fun headerSafe(value: String): String = value.filter { it.code in 0x20..0x7E }

/** Responds with the OpenAI error envelope `{"error":{"message","type"}}`. */
private suspend fun ApplicationCall.respondOpenAiError(status: HttpStatusCode, type: String, message: String) =
    respond(status, OpenAiErrorResponse(OpenAiErrorBody(message = message, type = type)))

/** Thrown while reading an upload that exceeds [MAX_UPLOAD_BYTES]. Maps to HTTP 413. */
private class UploadTooLargeException : IllegalArgumentException("El audio supera el límite de ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB.")

/** True when the client declares a body larger than [maxBytes]. Streamed bodies are capped while reading. */
private fun ApplicationCall.declaredLengthExceeds(maxBytes: Long): Boolean =
    (request.header(io.ktor.http.HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L) > maxBytes

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        if (out.size() + read > maxBytes) throw UploadTooLargeException()
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

private fun logApiCall(route: String, startMs: Long, status: HttpStatusCode, model: String?, detail: String, context: Context?) {
    PocketNodeLogger.log(
        method = "POST",
        route = route,
        statusCode = status.value,
        durationMs = System.currentTimeMillis() - startMs,
        model = model,
        detail = detail,
        context = context,
    )
}

class PocketNodeServer {
    companion object {
        const val PORT = 8080
    }

    private var server: EmbeddedServer<*, *>? = null
    private val serverDispatcher = Dispatchers.IO.limitedParallelism(2)
    private var serverJob: Job = SupervisorJob()
    private var serverScope: CoroutineScope = CoroutineScope(serverJob + serverDispatcher)

    fun start(context: Context? = null, preload: Boolean = true) {
        if (server != null) return
        if (serverJob.isCancelled) {
            serverJob = SupervisorJob()
            serverScope = CoroutineScope(serverJob + serverDispatcher)
        }
        context?.let {
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
                                if (call.declaredLengthExceeds(MAX_JSON_BYTES)) {
                                    call.respondText("Error: el cuerpo de la petición supera 1 MB.", status = HttpStatusCode.PayloadTooLarge)
                                    return@post
                                }
                                val req = call.receive<ChatRequest>()
                                val promptContext = PocketNodeInference.buildChatPromptContext(req.messages)
                                val toolChoice = parseToolChoice(req.tool_choice)
                                val toolsRequested = req.tools.orEmpty()
                                val toolInstruction = buildToolInstruction(toolsRequested, toolChoice)
                                val inferenceContext = if (toolInstruction == null) {
                                    promptContext
                                } else {
                                    promptContext.copy(
                                        systemInstruction = listOf(promptContext.systemInstruction, toolInstruction)
                                            .filter { it.isNotBlank() }
                                            .joinToString("\n\n"),
                                    )
                                }
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

                                val (model, err) = resolveAndInitChatModel(req.model, context)
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
                                    prompt = inferenceContext.userPrompt,
                                    systemInstruction = inferenceContext.systemInstruction,
                                    temperatureOverride = req.temperature,
                                    maxTokensOverride = req.maxTokens,
                                )
                                // Invalid tool JSON is not a call: it falls back to a plain text reply.
                                val toolCalls = parseToolCalls(fullResponse, toolsRequested, toolChoice)
                                val reply = ChatResponse(
                                    id = "chatcmpl-${System.currentTimeMillis()}",
                                    model = model.name,
                                    choices = listOf(
                                        if (toolCalls != null) {
                                            Choice(0, ResponseMessage("assistant", null, toolCalls), "tool_calls")
                                        } else {
                                            Choice(0, ResponseMessage("assistant", fullResponse, null), "stop")
                                        }
                                    ),
                                )
                                val toolDetail = if (toolsRequested.isEmpty()) "" else
                                    ", tools=${toolsRequested.size}, tool_calls=${toolCalls?.joinToString { it.function.name } ?: "none"}"
                                PocketNodeLogger.log(
                                    method = "POST",
                                    route = "/v1/chat/completions",
                                    statusCode = HttpStatusCode.OK.value,
                                    durationMs = System.currentTimeMillis() - startMs,
                                    model = model.name,
                                    detail = "msgs=${req.messages.size}, maxTokens=$effectiveMaxTokens, prompt=${promptContext.userPrompt.length} chars$toolDetail, reply=${fullResponse.take(80)}",
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

                        // OpenAI-compatible transcription. Fields: file (required), language, response_format.
                        // model, temperature, prompt and timestamp_granularities are accepted but ignored:
                        // the only engine is the phone's native SpeechRecognizer.
                        post(TRANSCRIPTION_ROUTE) {
                            val startMs = System.currentTimeMillis()
                            val modelName = AndroidNativeSttEngine.ENGINE_MODEL_ID
                            try {
                                var audioUpload: ByteArray? = null
                                var languageParam: String? = null
                                var responseFormatParam: String? = null

                                if (call.declaredLengthExceeds(MAX_UPLOAD_BYTES.toLong())) {
                                    val msg = "El audio supera el límite de ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB."
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, HttpStatusCode.PayloadTooLarge, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.PayloadTooLarge, "invalid_request_error", msg)
                                    return@post
                                }

                                try {
                                    call.receiveMultipart().forEachPart { part ->
                                        when (part) {
                                            is PartData.FileItem -> if (part.name == "file") {
                                                audioUpload = part.streamProvider().use { it.readBounded(MAX_UPLOAD_BYTES) }
                                            }
                                            is PartData.FormItem -> when (part.name) {
                                                "language" -> languageParam = part.value
                                                "response_format" -> responseFormatParam = part.value
                                            }
                                            else -> {}
                                        }
                                        part.dispose()
                                    }
                                } catch (e: Exception) {
                                    val tooLarge = e is UploadTooLargeException
                                    val status = if (tooLarge) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadRequest
                                    val msg = if (tooLarge) e.message!! else "Error parsing multipart audio request: ${e.message}"
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, status, modelName, msg, context)
                                    call.respondOpenAiError(status, "invalid_request_error", msg)
                                    return@post
                                }

                                val audioBytes = audioUpload
                                if (audioBytes == null || audioBytes.isEmpty()) {
                                    val msg = "No audio file uploaded in multipart field 'file'."
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, HttpStatusCode.BadRequest, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request_error", msg)
                                    return@post
                                }

                                val responseFormat = responseFormatParam?.lowercase()?.takeIf { it.isNotBlank() } ?: "json"
                                if (responseFormat !in SUPPORTED_TRANSCRIPTION_FORMATS) {
                                    val msg = "Unsupported response_format '$responseFormat'. Supported: json, text, srt, verbose_json, vtt."
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, HttpStatusCode.BadRequest, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request_error", msg)
                                    return@post
                                }

                                val appContext = context?.applicationContext
                                if (appContext == null) {
                                    val msg = "Android context unavailable for native STT service."
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, HttpStatusCode.ServiceUnavailable, modelName, msg, null)
                                    call.respondOpenAiError(HttpStatusCode.ServiceUnavailable, "server_error", msg)
                                    return@post
                                }

                                call.response.headers.append(MODEL_HEADER, headerSafe(modelName))
                                val effectiveLanguage = languageParam?.takeIf { it.isNotBlank() } ?: PocketNodeInference.DEFAULT_LANGUAGE

                                val sttResult = AndroidNativeSttEngine.transcribe(
                                    context = appContext,
                                    rawAudioBytes = audioBytes,
                                    language = effectiveLanguage,
                                    preferOffline = PocketNodeState.preferOfflineNativeStt,
                                )

                                sttResult.onFailure { err ->
                                    val (status, type) = when (err) {
                                        is IllegalArgumentException -> HttpStatusCode.BadRequest to "invalid_request_error"
                                        is SttBusyException -> HttpStatusCode.TooManyRequests to "rate_limit_error"
                                        else -> HttpStatusCode.ServiceUnavailable to "server_error"
                                    }
                                    val msg = err.message ?: "Error en servicio STT nativo del móvil."
                                    logApiCall(TRANSCRIPTION_ROUTE, startMs, status, modelName, msg, appContext)
                                    call.respondOpenAiError(status, type, msg)
                                    return@post
                                }

                                val output = sttResult.getOrThrow()
                                val timingsHeaderValue = output.timings.toHeaderValue()
                                call.response.headers.append(TIMINGS_HEADER, timingsHeaderValue)

                                logApiCall(
                                    TRANSCRIPTION_ROUTE,
                                    startMs,
                                    HttpStatusCode.OK,
                                    output.modelName,
                                    "audio=${audioBytes.size}B, lang=$effectiveLanguage, format=$responseFormat, text=${output.text.ifEmpty { "(vacío)" }.take(60)} | timings=$timingsHeaderValue",
                                    appContext,
                                )
                                when (responseFormat) {
                                    "text" -> call.respondText(output.text, ContentType.Text.Plain)
                                    "srt" -> call.respondText(formatSrt(output.segments), ContentType.Text.Plain)
                                    "vtt" -> call.respondText(formatVtt(output.segments), ContentType.parse("text/vtt"))
                                    "verbose_json" -> call.respond(
                                        VerboseTranscriptionResponse(
                                            language = effectiveLanguage,
                                            duration = output.timings.audioSeconds.toDouble(),
                                            text = output.text,
                                            segments = output.segments.mapIndexed { index, segment ->
                                                VerboseSegment(index, segment.start, segment.end, segment.text)
                                            },
                                        )
                                    )
                                    else -> call.respond(TranscriptionResponse(text = output.text))
                                }
                            } catch (t: Throwable) {
                                Log.e("PocketNodeServer", "Unhandled error in $TRANSCRIPTION_ROUTE", t)
                                val msg = "Internal Server Error: ${t.message}"
                                logApiCall(TRANSCRIPTION_ROUTE, startMs, HttpStatusCode.InternalServerError, modelName, "Exception: ${t.message}", context)
                                call.respondOpenAiError(HttpStatusCode.InternalServerError, "server_error", msg)
                            }
                        }
                        // OpenAI-compatible speech synthesis with the phone's own TTS engine. `model` is accepted and ignored.
                        post(SPEECH_ROUTE) {
                            val startMs = System.currentTimeMillis()
                            val modelName = AndroidNativeTtsEngine.ENGINE_MODEL_ID
                            try {
                                if (call.declaredLengthExceeds(MAX_SPEECH_BODY_BYTES)) {
                                    val msg = "El cuerpo de la petición supera $MAX_SPEECH_BODY_BYTES bytes."
                                    logApiCall(SPEECH_ROUTE, startMs, HttpStatusCode.PayloadTooLarge, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.PayloadTooLarge, "invalid_request_error", msg)
                                    return@post
                                }
                                val req = try {
                                    call.receive<SpeechRequest>()
                                } catch (e: Exception) {
                                    val msg = "Cuerpo JSON inválido: ${e.message}"
                                    logApiCall(SPEECH_ROUTE, startMs, HttpStatusCode.BadRequest, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request_error", msg)
                                    return@post
                                }

                                val format = req.responseFormat?.lowercase()?.takeIf { it.isNotBlank() } ?: "wav"
                                AndroidNativeTtsEngine.validateSpeechRequest(req.input, req.speed, format)?.let { msg ->
                                    logApiCall(SPEECH_ROUTE, startMs, HttpStatusCode.BadRequest, modelName, msg, context)
                                    call.respondOpenAiError(HttpStatusCode.BadRequest, "invalid_request_error", msg)
                                    return@post
                                }

                                val appContext = context?.applicationContext
                                if (appContext == null) {
                                    val msg = "Android context unavailable for native TTS service."
                                    logApiCall(SPEECH_ROUTE, startMs, HttpStatusCode.ServiceUnavailable, modelName, msg, null)
                                    call.respondOpenAiError(HttpStatusCode.ServiceUnavailable, "server_error", msg)
                                    return@post
                                }

                                call.response.headers.append(MODEL_HEADER, headerSafe(modelName))
                                val result = AndroidNativeTtsEngine.synthesize(
                                    context = appContext,
                                    input = req.input,
                                    voice = req.voice,
                                    speed = (req.speed ?: 1.0).toFloat(),
                                    format = format,
                                )
                                result.onFailure { err ->
                                    val (status, type) = when (err) {
                                        is IllegalArgumentException -> HttpStatusCode.BadRequest to "invalid_request_error"
                                        is TtsBusyException -> HttpStatusCode.TooManyRequests to "rate_limit_error"
                                        else -> HttpStatusCode.ServiceUnavailable to "server_error"
                                    }
                                    val msg = err.message ?: "Error en servicio TTS nativo del móvil."
                                    logApiCall(SPEECH_ROUTE, startMs, status, modelName, msg, appContext)
                                    call.respondOpenAiError(status, type, msg)
                                    return@post
                                }

                                val output = result.getOrThrow()
                                call.response.headers.append(TIMINGS_HEADER, headerSafe(output.timings))
                                logApiCall(
                                    SPEECH_ROUTE,
                                    startMs,
                                    HttpStatusCode.OK,
                                    modelName,
                                    "chars=${req.input.length}, format=$format, bytes=${output.audio.size} | ${output.timings}",
                                    appContext,
                                )
                                call.respondBytes(
                                    output.audio,
                                    ContentType.parse(
                                        when (format) {
                                            "pcm" -> "audio/pcm"
                                            "aac" -> "audio/aac"
                                            else -> "audio/wav"
                                        }
                                    ),
                                )
                            } catch (t: Throwable) {
                                Log.e("PocketNodeServer", "Unhandled error in $SPEECH_ROUTE", t)
                                val msg = "Internal Server Error: ${t.message}"
                                logApiCall(SPEECH_ROUTE, startMs, HttpStatusCode.InternalServerError, modelName, "Exception: ${t.message}", context)
                                call.respondOpenAiError(HttpStatusCode.InternalServerError, "server_error", msg)
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
                    model = PocketNodeState.activeChatModel?.name ?: AndroidNativeSttEngine.ENGINE_MODEL_ID,
                    detail = "Servidor HTTP iniciado en puerto $PORT (STT=${AndroidNativeSttEngine.ENGINE_MODEL_ID}, maxTokens=${PocketNodeState.configuredMaxTokens}, idle=${PocketNodeState.configuredIdleMinutes}m)",
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
        if (preload) context?.let { preloadActiveModels(it) }
    }

    /** Preloads the active Chat LLM model into memory immediately on server start. */
    private fun preloadActiveModels(context: Context) {
        serverScope.launch {
            val chatModel = PocketNodeState.activeChatModel ?: return@launch
            val startMs = System.currentTimeMillis()
            PocketNodeInference.preloadModel(context, chatModel, forAudio = false)
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
    }

    private suspend fun resolveAndInitChatModel(
        reqModelName: String,
        context: Context?,
    ): Pair<com.google.ai.edge.gallery.data.Model?, String?> {
        PocketNodeState.syncSharedModels()
        var model = if (reqModelName.isNotEmpty() && context != null) {
            PocketNodeModelResolver.findModelByNameOrId(context, reqModelName)
        } else null

        if (model == null) {
            model = PocketNodeState.activeChatModel ?: PocketNodeState.activeModel
        }

        if (model == null && context != null) {
            val allModels = PocketNodeModelResolver.getAllModels(context)
            model = allModels.find { java.io.File(it.getPath(context)).exists() } ?: allModels.firstOrNull()
            if (model != null) PocketNodeState.activeChatModel = model
        }

        if (model == null) {
            return Pair(null, "Error: No active chat model available. Please select a model in Pocket Node Server.")
        }

        if (model.instance == null && context != null) {
            val modelFile = java.io.File(model.getPath(context))
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return Pair(null, "Error: Model file '${model.name}' (${model.downloadFileName}) is not downloaded on device yet. Please download it in the Gallery App first.")
            }

            PocketNodeInference.preloadModel(context, model, forAudio = false).onFailure { e ->
                return Pair(null, "Error initializing model '${model.name}': ${e.message}")
            }
        }

        if (model.instance == null) {
            return Pair(null, "Error: Model '${model.name}' is not initialized in memory.")
        }

        return Pair(model, null)
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
        CoroutineScope(serverDispatcher).launch {
            PocketNodeInference.releaseAllLoadedModels()
        }
    }
}
