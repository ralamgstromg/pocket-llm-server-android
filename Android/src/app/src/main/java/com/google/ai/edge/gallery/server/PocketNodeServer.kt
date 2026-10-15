package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.openai.ApiKeyAuth
import com.google.ai.edge.gallery.server.openai.BodyLimit
import com.google.ai.edge.gallery.server.openai.ChatCompletionEngine
import com.google.ai.edge.gallery.server.openai.ChatCompletionRequest
import com.google.ai.edge.gallery.server.openai.ChatEvent
import com.google.ai.edge.gallery.server.openai.ChatRequestParser
import com.google.ai.edge.gallery.server.openai.ChunkDelta
import com.google.ai.edge.gallery.server.openai.EncodedAudio
import com.google.ai.edge.gallery.server.openai.ErrorResponse
import com.google.ai.edge.gallery.server.openai.ModelList
import com.google.ai.edge.gallery.server.openai.ModelObject
import com.google.ai.edge.gallery.server.openai.SkillList
import com.google.ai.edge.gallery.server.openai.SkillObject
import com.google.ai.edge.gallery.server.openai.OpenAiException
import com.google.ai.edge.gallery.server.openai.OpenAiJson
import com.google.ai.edge.gallery.server.openai.ParsedChatRequest
import com.google.ai.edge.gallery.server.openai.ParsedToolCall
import com.google.ai.edge.gallery.server.openai.ResponseUtils
import com.google.ai.edge.gallery.server.openai.StopSequenceFilter
import com.google.ai.edge.gallery.server.openai.ThinkStreamSplitter
import com.google.ai.edge.gallery.server.openai.TokenCounts
import com.google.ai.edge.gallery.server.openai.ToolCallTextParser
import com.google.ai.edge.gallery.server.openai.TranscriptionResponse
import com.google.ai.edge.gallery.server.openai.Usage
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.io.Writer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

private const val TAG = "PocketNodeServer"
private const val PORT = 8080

/** Largest accepted request body (base64 images and audio included). */
private const val MAX_BODY_BYTES = 20L * 1024 * 1024

/** HTTP server exposing an OpenAI-compatible REST API on top of the on-device models. */
class PocketNodeServer {
  private var server: EmbeddedServer<*, *>? = null
  private val serverMutexMap = ConcurrentHashMap<String, Mutex>()

  /** Serializes model loading so concurrent first requests never load two engines. */
  private val initMutex = Mutex()

  fun start(context: Context? = null) {
    if (server != null) return
    PocketNodeState.isServerRunning = true
    PocketNodeState.lastStartupError = null
    context?.let { PocketNodeState.loadPreferences(it) }
    val apiKey = PocketNodeState.apiKey
    val corsEnabled = PocketNodeState.corsEnabled

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val newServer =
          embeddedServer(CIO, port = PORT) {
            if (corsEnabled) {
              install(CORS) {
                anyHost() // Allow n8n, browsers and any local network client.
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
              }
            }
            install(ContentNegotiation) { json(OpenAiJson) }

            routing {
              get("/") { call.respondText("Pocket Node AI is running! OpenAI-compatible API at /v1") }

              get("/v1/models") { handle(call, apiKey) { call.respond(ModelList(data = listModels(context))) } }

              get("/v1/models/{id...}") {
                handle(call, apiKey) {
                  val id = call.parameters.getAll("id")?.joinToString("/").orEmpty()
                  val model =
                    listModels(context).find { it.id.equals(id, ignoreCase = true) }
                      ?: throw OpenAiException(404, "The model '$id' does not exist.", param = "model", code = "model_not_found")
                  call.respond(model)
                }
              }

              post("/v1/chat/completions") { handle(call, apiKey) { chatCompletions(call, context) } }

              post("/v1/audio/transcriptions") { handle(call, apiKey) { transcriptions(call, context) } }

              get("/v1/skills") { handle(call, apiKey) { call.respond(SkillList(data = listSkills(context))) } }
            }
          }
        server = newServer.start(wait = false)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start Ktor HTTP server", e)
        PocketNodeState.isServerRunning = false
        PocketNodeState.lastStartupError =
          "Error al iniciar servidor (puerto $PORT en uso o error de red): ${e.message}"
        server = null
      }
    }
  }

  fun stop() {
    PocketNodeState.isServerRunning = false
    server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
    server = null
  }

  /** Applies authentication and body limits, and turns failures into OpenAI error responses. */
  private suspend fun handle(call: ApplicationCall, apiKey: String, block: suspend () -> Unit) {
    try {
      if (!ApiKeyAuth.isAuthorized(call.request.headers[HttpHeaders.Authorization], apiKey)) {
        throw OpenAiException(401, "Invalid or missing API key. Use 'Authorization: Bearer <key>'.", code = "invalid_api_key")
      }
      val length = call.request.contentLength()
      if (length != null && length > MAX_BODY_BYTES) {
        throw OpenAiException(413, "Request body exceeds ${MAX_BODY_BYTES / (1024 * 1024)} MB.", code = "request_too_large")
      }
      block()
    } catch (e: OpenAiException) {
      call.respond(HttpStatusCode.fromValue(e.status), e.toResponse())
    } catch (e: SerializationException) {
      call.respond(HttpStatusCode.BadRequest, OpenAiException(400, "Invalid JSON body: ${e.message}").toResponse())
    } catch (e: io.ktor.server.plugins.BadRequestException) {
      call.respond(HttpStatusCode.BadRequest, OpenAiException(400, "Invalid request body: ${e.cause?.message ?: e.message}").toResponse())
    } catch (t: Throwable) {
      Log.e(TAG, "Unhandled error in ${call.request.local.uri}", t)
      call.respond(HttpStatusCode.InternalServerError, OpenAiException(500, "Internal server error: ${t.message}").toResponse())
    }
  }

  // -------------------------------------------------------------------------------------------
  // /v1/models
  // -------------------------------------------------------------------------------------------

  private fun listSkills(context: Context?): List<SkillObject> {
    if (context == null) return emptyList()
    val list = mutableListOf<SkillObject>()
    try {
      val pbFile = File(context.filesDir, "datastore/skills.pb")
      if (pbFile.exists()) {
        val skills = pbFile.inputStream().use { com.google.ai.edge.gallery.proto.Skills.parseFrom(it) }
        for (s in skills.skillList) {
          list.add(
            SkillObject(
              id = s.name,
              name = s.name,
              description = s.description ?: "",
              selected = s.selected,
              builtIn = s.builtIn,
            )
          )
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed reading skills for /v1/skills: ${e.message}")
    }
    return list
  }

  private fun listModels(context: Context?): List<ModelObject> {
    if (context == null) return emptyList()
    val created = System.currentTimeMillis() / 1000
    return PocketNodeModelResolver.getAllModels(context).map { m ->
      ModelObject(
        id = m.name,
        created = created,
        ownedBy = "pocket-node",
        downloaded = File(m.getPath(context)).let { it.exists() && it.length() > 0 },
      )
    }
  }

  // -------------------------------------------------------------------------------------------
  // /v1/chat/completions
  // -------------------------------------------------------------------------------------------

  private suspend fun chatCompletions(call: ApplicationCall, context: Context?) {
    // Read with a hard cap: chunked uploads carry no Content-Length.
    val body = BodyLimit.readLimited(call.receiveChannel(), MAX_BODY_BYTES).decodeToString()
    val req = ChatRequestParser.parse(OpenAiJson.decodeFromString(ChatCompletionRequest.serializer(), body))
    val needsAudio = (req.history + req.current).any { it.audios.isNotEmpty() }
    val model = resolveAndInitModel(req.model, context, isAudio = needsAudio)
    val decodeAudio: (EncodedAudio) -> ByteArray = { audio ->
      if (context != null) AudioDecoderHelper.decodeToMonoPcmWav(context, audio.bytes) else audio.bytes
    }
    val id = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
    val created = System.currentTimeMillis() / 1000
    val mutex = serverMutexMap.getOrPut(model.name) { Mutex() }

    mutex.withLock {
      if (req.stream) {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondTextWriter(ContentType.Text.EventStream) {
          streamCompletion(this, req, model, id, created, decodeAudio)
        }
      } else {
        call.respond(completeChat(req, model, id, created, decodeAudio))
      }
    }
  }

  private suspend fun completeChat(
    req: ParsedChatRequest,
    model: Model,
    id: String,
    created: Long,
    decodeAudio: (EncodedAudio) -> ByteArray,
  ): Any {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val nativeCalls = mutableListOf<Pair<String, String>>()
    val counts =
      ChatCompletionEngine.run(model, req, decodeAudio) { event ->
        when (event) {
          is ChatEvent.Delta -> {
            text.append(event.text)
            event.reasoning?.let { reasoning.append(it) }
          }
          is ChatEvent.ToolCalls -> nativeCalls.addAll(event.calls)
        }
      }

    val (rawContent, inlineReasoning) = ResponseUtils.splitThinking(text.toString())
    val stopFilter = StopSequenceFilter(req.stop)
    var content = stopFilter.feed(rawContent) + stopFilter.flush()
    val toolCalls = resolveToolCalls(req, nativeCalls, content).let { (rest, calls) ->
      content = rest
      calls
    }
    val allReasoning = listOf(reasoning.toString(), inlineReasoning.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    return ResponseUtils.completion(
      id = id,
      created = created,
      model = model.name,
      content = content,
      reasoning = allReasoning.ifEmpty { null },
      toolCalls = toolCalls,
      finishReason = finishReason(req, toolCalls, stopFilter.stopped, counts, text.length),
      usage = usage(counts, req, text.length),
    )
  }

  private suspend fun streamCompletion(
    writer: Writer,
    req: ParsedChatRequest,
    model: Model,
    id: String,
    created: Long,
    decodeAudio: (EncodedAudio) -> ByteArray,
  ) {
    fun send(delta: ChunkDelta, finishReason: String? = null, usage: Usage? = null) {
      writer.write(ResponseUtils.sse(ResponseUtils.chunk(id, created, model.name, delta, finishReason, usage)))
      writer.flush()
    }

    // With tools, content is held back until the end so text-encoded tool calls can be parsed.
    val bufferContent = req.tools.isNotEmpty()
    val stopFilter = StopSequenceFilter(req.stop)
    val thinkSplitter = ThinkStreamSplitter(assumeThinkingFirst = req.enableThinking == true)
    val heldContent = StringBuilder()
    var producedChars = 0
    val nativeCalls = mutableListOf<Pair<String, String>>()

    send(ChunkDelta(role = "assistant", content = ""))
    try {
      val counts =
        ChatCompletionEngine.run(model, req, decodeAudio) { event ->
          when (event) {
            is ChatEvent.Delta -> {
              producedChars += event.text.length
              val (text, inlineReasoning) = thinkSplitter.feed(event.text)
              val reasoningDelta = (event.reasoning.orEmpty() + inlineReasoning)
              if (reasoningDelta.isNotEmpty()) send(ChunkDelta(reasoningContent = reasoningDelta))
              val visible = stopFilter.feed(text)
              if (bufferContent) heldContent.append(visible) else if (visible.isNotEmpty()) send(ChunkDelta(content = visible))
            }
            is ChatEvent.ToolCalls -> nativeCalls.addAll(event.calls)
          }
        }
      val (thinkTail, reasoningTail) = thinkSplitter.flush()
      if (reasoningTail.isNotEmpty()) send(ChunkDelta(reasoningContent = reasoningTail))
      val tail = stopFilter.feed(thinkTail) + stopFilter.flush()
      var toolCalls = emptyList<ParsedToolCall>()
      if (bufferContent) {
        heldContent.append(tail)
        val (rest, calls) = resolveToolCalls(req, nativeCalls, heldContent.toString())
        toolCalls = calls
        if (rest.isNotEmpty()) send(ChunkDelta(content = rest))
        if (calls.isNotEmpty()) send(ChunkDelta(toolCalls = ResponseUtils.toolCallDtos(calls, withIndex = true)))
      } else if (tail.isNotEmpty()) {
        send(ChunkDelta(content = tail))
      }
      send(ChunkDelta(), finishReason = finishReason(req, toolCalls, stopFilter.stopped, counts, producedChars))
      if (req.includeUsage) send(ChunkDelta(), usage = usage(counts, req, producedChars))
    } catch (e: OpenAiException) {
      writer.write("data: ${OpenAiJson.encodeToString(ErrorResponse.serializer(), e.toResponse())}\n\n")
    } catch (t: Throwable) {
      Log.e(TAG, "Streaming inference failed", t)
      val err = OpenAiException(500, "Inference failed: ${t.message}").toResponse()
      writer.write("data: ${OpenAiJson.encodeToString(ErrorResponse.serializer(), err)}\n\n")
    }
    writer.write(ResponseUtils.SSE_DONE)
    writer.flush()
  }

  /** Native tool calls win; otherwise tool calls written as text are parsed (generic models). */
  private fun resolveToolCalls(
    req: ParsedChatRequest,
    nativeCalls: List<Pair<String, String>>,
    content: String,
  ): Pair<String, List<ParsedToolCall>> {
    if (req.tools.isEmpty()) return Pair(content, emptyList())
    var (rest, calls) =
      if (nativeCalls.isNotEmpty()) Pair(content, nativeCalls.map { ParsedToolCall(newCallId(), it.first, it.second) })
      else ToolCallTextParser.parse(content, ::newCallId)
    val allowed = req.tools.map { it.name }.toSet()
    calls = calls.filter { it.name in allowed }
    if (!req.parallelToolCalls) calls = calls.take(1)
    return Pair(rest, calls)
  }

  private fun newCallId() = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"

  private fun finishReason(
    req: ParsedChatRequest,
    toolCalls: List<ParsedToolCall>,
    stopped: Boolean,
    counts: TokenCounts,
    completionChars: Int,
  ): String =
    ResponseUtils.finishReason(
      hasToolCalls = toolCalls.isNotEmpty(),
      stopped = stopped,
      maxTokens = req.maxTokens,
      completionTokens = counts.completion ?: ResponseUtils.estimateTokens(completionChars),
    )

  private fun usage(counts: TokenCounts, req: ParsedChatRequest, completionChars: Int): Usage {
    val promptChars = (req.history + req.current).sumOf { it.text.length } + (req.systemPrompt?.length ?: 0)
    val prompt = counts.prompt ?: ResponseUtils.estimateTokens(promptChars)
    val completion = counts.completion ?: ResponseUtils.estimateTokens(completionChars)
    val estimated = counts.prompt == null || counts.completion == null
    return Usage(prompt, completion, prompt + completion, estimated = if (estimated) true else null)
  }

  // -------------------------------------------------------------------------------------------
  // /v1/audio/transcriptions
  // -------------------------------------------------------------------------------------------

  private suspend fun transcriptions(call: ApplicationCall, context: Context?) {
    var rawAudioBytes: ByteArray? = null
    var modelParam = ""
    var promptParam: String? = null
    var languageParam: String? = null
    var responseFormatParam: String? = null

    try {
      call.receiveMultipart(formFieldLimit = MAX_BODY_BYTES).forEachPart { part ->
        when (part) {
          is PartData.FileItem -> rawAudioBytes = BodyLimit.readLimited(part.provider(), MAX_BODY_BYTES)
          is PartData.FormItem ->
            when (part.name) {
              "model" -> modelParam = part.value
              "prompt" -> promptParam = part.value
              "language" -> languageParam = part.value
              "response_format" -> responseFormatParam = part.value
            }
          else -> {}
        }
        part.release()
      }
    } catch (e: OpenAiException) {
      throw e
    } catch (e: Exception) {
      throw OpenAiException(400, "Error parsing multipart audio request: ${e.message}")
    }

    val format = responseFormatParam?.lowercase() ?: "json"
    if (format !in setOf("json", "text")) {
      throw OpenAiException(400, "Unsupported response_format '$format'. Use 'json' or 'text'.", param = "response_format")
    }
    val audio = rawAudioBytes
    if (audio == null || audio.isEmpty()) {
      throw OpenAiException(400, "No audio file uploaded in multipart field 'file'.", param = "file")
    }

    val audioModel = resolveAndInitModel(modelParam, context, isAudio = true)
    // Decode audio file (MP3, M4A, AAC, OGG, FLAC, WAV) to 16kHz Mono 16-bit PCM WAV.
    val processedAudioBytes = if (context != null) AudioDecoderHelper.decodeToMonoPcmWav(context, audio) else audio

    val prompt =
      when {
        !promptParam.isNullOrBlank() -> promptParam
        languageParam.equals("es", ignoreCase = true) || languageParam.equals("spanish", ignoreCase = true) ->
          "Entrega ÚNICAMENTE el texto hablado literal de este audio. No incluyas introducciones, etiquetas, comillas ni explicaciones."
        !languageParam.isNullOrBlank() ->
          "Output ONLY the verbatim spoken text from this audio in language ${languageParam}. Do NOT include any intro, explanation, labels, or extra words."
        else ->
          "Output ONLY the verbatim spoken text from this audio. Do NOT include any intro, explanation, labels, or extra words."
      }

    val mutex = serverMutexMap.getOrPut(audioModel.name) { Mutex() }
    val fullResponse = mutex.withLock { executeModelInference(audioModel, prompt, audioClips = listOf(processedAudioBytes)) }
    val cleanText = sanitizeSpokenText(fullResponse)
    if (format == "text") {
      call.respondText(cleanText, ContentType.Text.Plain)
    } else {
      call.respond(TranscriptionResponse(text = cleanText))
    }
  }

  // -------------------------------------------------------------------------------------------
  // Model resolution and single-turn inference.
  // -------------------------------------------------------------------------------------------

  /**
   * Finds the requested model (falls back to the model selected in the server dialog, so clients
   * that send names like "gpt-4o" keep working) and loads it into memory if needed.
   */
  private suspend fun resolveAndInitModel(reqModelName: String, context: Context?, isAudio: Boolean): Model {
    PocketNodeState.syncSharedModels()
    var model =
      if (reqModelName.isNotEmpty() && context != null) {
        PocketNodeModelResolver.findModelByNameOrId(context, reqModelName)
      } else null

    if (model == null) {
      model =
        if (isAudio) {
          PocketNodeState.activeAudioModel ?: PocketNodeState.activeChatModel ?: PocketNodeState.activeModel
        } else {
          PocketNodeState.activeChatModel ?: PocketNodeState.activeAudioModel ?: PocketNodeState.activeModel
        }
    }

    if (model == null && context != null) {
      val allModels = PocketNodeModelResolver.getAllModels(context)
      if (isAudio) {
        val audioCapable = allModels.filter { it.llmSupportAudio || it.bestForTaskIds.contains("llm_ask_audio") }
        model = audioCapable.find { File(it.getPath(context)).exists() } ?: audioCapable.firstOrNull() ?: allModels.firstOrNull()
        if (model != null) PocketNodeState.activeAudioModel = model
      } else {
        model = allModels.find { File(it.getPath(context)).exists() } ?: allModels.firstOrNull()
        if (model != null) PocketNodeState.activeChatModel = model
      }
    }

    if (model == null) {
      throw OpenAiException(503, "No active model available. Please select a model in Pocket Node Server.", code = "model_unavailable")
    }

    if (model.instance == null && context != null) initMutex.withLock {
      if (model.instance != null) return@withLock
      val modelFile = File(model.getPath(context))
      if (!modelFile.exists() || modelFile.length() == 0L) {
        throw OpenAiException(
          503,
          "Model file '${model.name}' (${model.downloadFileName}) is not downloaded on device yet. Please download it in the Gallery App first.",
          code = "model_not_downloaded",
        )
      }

      var initError: String? = null
      suspendCoroutine { continuation ->
        LlmChatModelHelper.initialize(
          context = context,
          model = model,
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          onDone = { errorMsg ->
            if (errorMsg.isNotEmpty()) initError = errorMsg
            continuation.resume(Unit)
          },
        )
      }

      if (initError != null || model.instance == null) {
        throw OpenAiException(
          503,
          "Error initializing model '${model.name}': ${initError ?: "Failed to load model into RAM"}",
          code = "model_load_failed",
        )
      }
    }

    if (model.instance == null) {
      throw OpenAiException(503, "Model '${model.name}' is not initialized in memory.", code = "model_not_loaded")
    }
    return model
  }

  /** Single-turn inference on the model's own conversation (used by transcriptions). */
  private suspend fun executeModelInference(model: Model, prompt: String, audioClips: List<ByteArray> = emptyList()): String {
    val fullResponse = StringBuilder()
    val isResumed = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
      suspendCoroutine<Unit> { continuation ->
        LlmChatModelHelper.runInference(
          model = model,
          input = prompt,
          audioClips = audioClips,
          resultListener = { partialResult, done, _ ->
            fullResponse.append(partialResult)
            if (done && isResumed.compareAndSet(false, true)) continuation.resume(Unit)
          },
          cleanUpListener = {},
          onError = { errorMsg ->
            fullResponse.append("\n[Error: $errorMsg]")
            if (isResumed.compareAndSet(false, true)) continuation.resume(Unit)
          },
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Inference failed for model ${model.name}", e)
      fullResponse.append("\n[Error: ${e.message}]")
    }
    return fullResponse.toString()
  }

  private fun sanitizeSpokenText(rawInput: String): String {
    var text = rawInput
    // 1. Remove thinking / reasoning blocks <think>...</think>
    text = text.replace(Regex("(?s)<think>.*?</think>"), "")
    text = text.replace(Regex("(?s)<transcript>.*?</transcript>"), "")

    // 2. Remove code blocks ```text ... ```
    text = text.replace(Regex("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```"), "$1")

    // 3. Remove common intro prefixes
    val prefixRegex =
      Regex(
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
    if (
      (text.startsWith("\"") && text.endsWith("\"")) ||
        (text.startsWith("'") && text.endsWith("'")) ||
        (text.startsWith("«") && text.endsWith("»"))
    ) {
      text = text.substring(1, text.length - 1).trim()
    }
    return text
  }
}
