package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.EmbeddedServer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Serializable
data class ChatRequest(val model: String = "", val messages: List<Message> = emptyList())

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatResponse(val id: String, val choices: List<Choice>)

@Serializable
data class Choice(val message: Message)

@Serializable
data class TranscriptionResponse(val text: String)

class PocketNodeServer {
    private var server: EmbeddedServer<*, *>? = null
    private val serverMutexMap = ConcurrentHashMap<String, Mutex>()

    fun start(context: Context? = null) {
        if (server != null) return
        PocketNodeState.isServerRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(CIO, port = 8080) {
                install(CORS) {
                    anyHost() // Allow connections from n8n or any local network client
                    allowHeader(io.ktor.http.HttpHeaders.ContentType)
                }
                install(ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }

                routing {
                    get("/") {
                        call.respondText("Pocket Node AI is running!")
                    }

                    post("/v1/chat/completions") {
                        try {
                            val req = call.receive<ChatRequest>()
                            val prompt = req.messages.lastOrNull()?.content ?: ""

                            PocketNodeState.syncSharedModels()
                            val ctx = context
                            var model = if (req.model.isNotEmpty() && ctx != null) {
                                PocketNodeModelResolver.findModelByNameOrId(ctx, req.model)
                            } else null

                            if (model == null) {
                                model = PocketNodeState.activeChatModel ?: PocketNodeState.activeAudioModel ?: PocketNodeState.activeModel
                            }

                            if (model == null && ctx != null) {
                                val allModels = PocketNodeModelResolver.getAllModels(ctx)
                                model = allModels.find { java.io.File(it.getPath(ctx)).exists() } ?: allModels.firstOrNull()
                                if (model != null) {
                                    PocketNodeState.activeChatModel = model
                                }
                            }

                            if (model == null) {
                                call.respondText("Error: No chat model available. Please select a model in Pocket Node Server.", status = HttpStatusCode.ServiceUnavailable)
                                return@post
                            }

                            if (model.instance == null && ctx != null) {
                                val modelFile = java.io.File(model.getPath(ctx))
                                if (!modelFile.exists() || modelFile.length() == 0L) {
                                    call.respondText("Error: Model file '${model.name}' (${model.downloadFileName}) is not downloaded on device yet. Please download it in the Gallery App first.", status = HttpStatusCode.ServiceUnavailable)
                                    return@post
                                }

                                var initError: String? = null
                                suspendCoroutine { continuation ->
                                    LlmChatModelHelper.initialize(
                                        context = ctx,
                                        model = model,
                                        supportImage = model.llmSupportImage,
                                        supportAudio = model.llmSupportAudio,
                                        onDone = { errorMsg ->
                                            if (errorMsg.isNotEmpty()) initError = errorMsg
                                            continuation.resume(Unit)
                                        }
                                    )
                                }

                                if (initError != null || model.instance == null) {
                                    call.respondText("Error initializing model '${model.name}': ${initError ?: "Failed to load model into RAM"}", status = HttpStatusCode.InternalServerError)
                                    return@post
                                }
                            }

                            if (model.instance == null) {
                                call.respondText("Error: Active model '${model.name}' is not initialized in memory.", status = HttpStatusCode.ServiceUnavailable)
                                return@post
                            }

                            val mutex = serverMutexMap.getOrPut(model.name) { Mutex() }
                            var fullResponse = ""
                            mutex.withLock {
                                val isResumed = AtomicBoolean(false)
                                try {
                                    suspendCoroutine { continuation ->
                                        LlmChatModelHelper.runInference(
                                            model = model,
                                            input = prompt,
                                            resultListener = { partialResult, done, _ ->
                                                fullResponse += partialResult
                                                if (done && isResumed.compareAndSet(false, true)) {
                                                    continuation.resume(Unit)
                                                }
                                            },
                                            cleanUpListener = {},
                                            onError = { errorMsg ->
                                                fullResponse += "\n[Error: $errorMsg]"
                                                if (isResumed.compareAndSet(false, true)) {
                                                    continuation.resume(Unit)
                                                }
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("PocketNodeServer", "Chat inference failed", e)
                                    if (isResumed.compareAndSet(false, true)) {
                                        fullResponse += "\n[Error: ${e.message}]"
                                    }
                                }
                            }

                            val reply = ChatResponse(
                                id = "chatcmpl-${System.currentTimeMillis()}",
                                choices = listOf(Choice(Message("assistant", fullResponse)))
                            )
                            call.respond(reply)
                        } catch (t: Throwable) {
                            Log.e("PocketNodeServer", "Unhandled error in /v1/chat/completions", t)
                            call.respondText("Internal Server Error: ${t.message}", status = HttpStatusCode.InternalServerError)
                        }
                    }

                    post("/v1/audio/transcriptions") {
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
                                call.respondText("Error parsing multipart audio request: ${e.message}", status = HttpStatusCode.BadRequest)
                                return@post
                            }

                            if (rawAudioBytes == null || rawAudioBytes!!.isEmpty()) {
                                call.respondText("Error: No audio file uploaded in multipart field 'file'.", status = HttpStatusCode.BadRequest)
                                return@post
                            }

                            val ctx = context
                            var audioModel = PocketNodeState.activeAudioModel ?: PocketNodeState.activeChatModel
                            if (audioModel == null && ctx != null) {
                                val allModels = PocketNodeModelResolver.getAllModels(ctx)
                                val audioCapable = allModels.filter { it.llmSupportAudio || it.bestForTaskIds.contains("llm_ask_audio") }
                                audioModel = audioCapable.find { java.io.File(it.getPath(ctx)).exists() } ?: audioCapable.firstOrNull() ?: allModels.firstOrNull()
                                if (audioModel != null) {
                                    PocketNodeState.activeAudioModel = audioModel
                                }
                            }

                            if (audioModel == null) {
                                call.respondText("Error: No active audio/STT model selected. Please select a model in Pocket Node Server.", status = HttpStatusCode.ServiceUnavailable)
                                return@post
                            }

                            if (audioModel.instance == null && ctx != null) {
                                val modelFile = java.io.File(audioModel.getPath(ctx))
                                if (!modelFile.exists() || modelFile.length() == 0L) {
                                    call.respondText("Error: Audio model file '${audioModel.name}' (${audioModel.downloadFileName}) is not downloaded on device yet. Please download it in the app first.", status = HttpStatusCode.ServiceUnavailable)
                                    return@post
                                }

                                var initError: String? = null
                                suspendCoroutine { continuation ->
                                    LlmChatModelHelper.initialize(
                                        context = ctx,
                                        model = audioModel,
                                        supportImage = audioModel.llmSupportImage,
                                        supportAudio = audioModel.llmSupportAudio,
                                        onDone = { errorMsg ->
                                            if (errorMsg.isNotEmpty()) initError = errorMsg
                                            continuation.resume(Unit)
                                        }
                                    )
                                }

                                if (initError != null || audioModel.instance == null) {
                                    call.respondText("Error initializing audio model '${audioModel.name}': ${initError ?: "Failed to load model into RAM"}", status = HttpStatusCode.InternalServerError)
                                    return@post
                                }
                            }

                            if (audioModel.instance == null) {
                                call.respondText("Error: Audio model '${audioModel.name}' is not initialized in memory.", status = HttpStatusCode.ServiceUnavailable)
                                return@post
                            }

                            // Decode audio file (MP3, M4A, AAC, OGG, FLAC, WAV) to 16kHz Mono 16-bit PCM WAV
                            val processedAudioBytes = if (context != null) {
                                AudioDecoderHelper.decodeToMonoPcmWav(context, rawAudioBytes!!)
                            } else {
                                rawAudioBytes!!
                            }

                            val prompt = when {
                                !promptParam.isNullOrBlank() -> promptParam!!
                                languageParam.equals("es", ignoreCase = true) || languageParam.equals("spanish", ignoreCase = true) ->
                                    "Entrega ÚNICAMENTE el texto hablado literal de este audio. No incluyas introducciones, etiquetas, comillas ni explicaciones."
                                !languageParam.isNullOrBlank() ->
                                    "Output ONLY the verbatim spoken text from this audio in language ${languageParam}. Do NOT include any intro, explanation, labels, or extra words."
                                else ->
                                    "Output ONLY the verbatim spoken text from this audio. Do NOT include any intro, explanation, labels, or extra words."
                            }

                            val mutex = serverMutexMap.getOrPut(audioModel.name) { Mutex() }
                            var fullResponse = ""
                            mutex.withLock {
                                val isResumed = AtomicBoolean(false)
                                try {
                                    suspendCoroutine { continuation ->
                                        LlmChatModelHelper.runInference(
                                            model = audioModel,
                                            input = prompt,
                                            audioClips = listOf(processedAudioBytes),
                                            resultListener = { partialResult, done, _ ->
                                                fullResponse += partialResult
                                                if (done && isResumed.compareAndSet(false, true)) {
                                                    continuation.resume(Unit)
                                                }
                                            },
                                            cleanUpListener = {},
                                            onError = { errorMsg ->
                                                fullResponse += "\n[Error: $errorMsg]"
                                                if (isResumed.compareAndSet(false, true)) {
                                                    continuation.resume(Unit)
                                                }
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("PocketNodeServer", "Audio inference failed", e)
                                    if (isResumed.compareAndSet(false, true)) {
                                        fullResponse += "\n[Error: ${e.message}]"
                                    }
                                }
                            }

                            val cleanText = sanitizeSpokenText(fullResponse)
                            if (responseFormatParam.equals("text", ignoreCase = true)) {
                                call.respondText(cleanText, ContentType.Text.Plain)
                            } else {
                                call.respond(TranscriptionResponse(text = cleanText))
                            }
                        } catch (t: Throwable) {
                            Log.e("PocketNodeServer", "Unhandled error in /v1/audio/transcriptions", t)
                            call.respondText("Internal Server Error: ${t.message}", status = HttpStatusCode.InternalServerError)
                        }
                    }
                }
            }.start(wait = false)
        }
    }

    fun stop() {
        PocketNodeState.isServerRunning = false
        server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
        server = null
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

