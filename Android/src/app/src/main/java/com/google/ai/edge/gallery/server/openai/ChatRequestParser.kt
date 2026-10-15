package com.google.ai.edge.gallery.server.openai

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TurnRole {
  USER,
  ASSISTANT,
  TOOL,
}

data class EncodedAudio(val bytes: ByteArray, val format: String)

data class ParsedToolCall(val id: String, val name: String, val argumentsJson: String)

/** One non-system message of the conversation, already decoded. */
data class ParsedTurn(
  val role: TurnRole,
  val text: String,
  val images: List<ByteArray> = emptyList(),
  val audios: List<EncodedAudio> = emptyList(),
  val toolCalls: List<ParsedToolCall> = emptyList(),
  val toolCallId: String? = null,
  val toolName: String? = null,
)

sealed interface ToolChoice {
  data object Auto : ToolChoice

  data object None : ToolChoice

  data object Required : ToolChoice

  data class Named(val name: String) : ToolChoice
}

sealed interface ResponseFormatSpec {
  data object Text : ResponseFormatSpec

  data object JsonObject : ResponseFormatSpec

  data class JsonSchema(val schemaJson: String) : ResponseFormatSpec
}

/** A validated chat completion request, independent of the inference runtime. */
data class ParsedChatRequest(
  val model: String,
  val systemPrompt: String?,
  /** Turns before the ones being answered. */
  val history: List<ParsedTurn>,
  /** The turn(s) to answer: one user turn, or the trailing tool results. */
  val current: List<ParsedTurn>,
  val temperature: Double?,
  val topP: Double?,
  val topK: Int?,
  val maxTokens: Int?,
  val stop: List<String>,
  val seed: Int?,
  val stream: Boolean,
  val includeUsage: Boolean,
  val tools: List<FunctionDefinition>,
  val toolChoice: ToolChoice,
  val parallelToolCalls: Boolean,
  val responseFormat: ResponseFormatSpec,
  /** Null keeps the model default. */
  val enableThinking: Boolean?,
  /** Number of completions to produce (run one after another). */
  val n: Int = 1,
)

/** Validates an OpenAI chat completion request and turns it into a [ParsedChatRequest]. */
object ChatRequestParser {
  private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
  private val DATA_URI_REGEX = Regex("^data:([^;,]+)?(;[^,]*)?;base64,(.*)$", RegexOption.DOT_MATCHES_ALL)
  private const val MAX_STOP_SEQUENCES = 4
  private const val MAX_N = 8

  fun parse(req: ChatCompletionRequest): ParsedChatRequest {
    if (req.messages.isEmpty()) invalid("'messages' must contain at least one message.", "messages")
    req.n?.let { if (it !in 1..MAX_N) invalid("'n' must be between 1 and $MAX_N.", "n") }
    if ((req.n ?: 1) > 1 && req.stream) invalid("'n' > 1 is not supported together with 'stream'.", "n")
    req.temperature?.let { if (it < 0.0 || it > 2.0) invalid("'temperature' must be between 0 and 2.", "temperature") }
    req.topP?.let { if (it < 0.0 || it > 1.0) invalid("'top_p' must be between 0 and 1.", "top_p") }
    req.topK?.let { if (it < 1) invalid("'top_k' must be >= 1.", "top_k") }
    val maxTokens = req.maxCompletionTokens ?: req.maxTokens
    maxTokens?.let {
      if (it < 1) invalid("'max_completion_tokens' must be >= 1.", if (req.maxCompletionTokens != null) "max_completion_tokens" else "max_tokens")
    }

    var tools = parseTools(req.tools)
    val toolChoice = parseToolChoice(req.toolChoice, tools)
    tools =
      when (toolChoice) {
        ToolChoice.None -> emptyList()
        is ToolChoice.Named -> tools.filter { it.name == toolChoice.name }
        else -> tools
      }

    val systemParts = mutableListOf<String>()
    val turns = mutableListOf<ParsedTurn>()
    val toolNamesById = mutableMapOf<String, String>()
    req.messages.forEachIndexed { i, msg ->
      when (msg.role) {
        "system",
        "developer" -> systemParts.add(parseContent(msg.content, i).text)
        "user" -> {
          val c = parseContent(msg.content, i)
          turns.add(ParsedTurn(TurnRole.USER, c.text, c.images, c.audios))
        }
        "assistant" -> {
          val calls =
            msg.toolCalls.orEmpty().mapIndexed { j, call ->
              val name = call.function.name
              if (name.isNullOrBlank()) invalid("Tool call is missing 'function.name'.", "messages[$i].tool_calls[$j].function.name")
              val id = call.id ?: "call_${i}_$j"
              toolNamesById[id] = name
              ParsedToolCall(id, name, call.function.arguments ?: "{}")
            }
          turns.add(ParsedTurn(TurnRole.ASSISTANT, parseContent(msg.content, i, textOnly = true).text, toolCalls = calls))
        }
        "tool" -> {
          val id = msg.toolCallId ?: invalid("'tool_call_id' is required for tool messages.", "messages[$i].tool_call_id")
          val name =
            toolNamesById[id] ?: invalid("No assistant tool call with id '$id' precedes this tool message.", "messages[$i].tool_call_id")
          turns.add(ParsedTurn(TurnRole.TOOL, parseContent(msg.content, i, textOnly = true).text, toolCallId = id, toolName = name))
        }
        else -> invalid("Unsupported role '${msg.role}'.", "messages[$i].role")
      }
    }

    if (turns.isEmpty()) invalid("At least one user message is required.", "messages")
    val current: List<ParsedTurn>
    val history: List<ParsedTurn>
    when (turns.last().role) {
      TurnRole.USER -> {
        current = listOf(turns.last())
        history = turns.dropLast(1)
      }
      TurnRole.TOOL -> {
        val firstTrailingTool = turns.indexOfLast { it.role != TurnRole.TOOL } + 1
        current = turns.subList(firstTrailingTool, turns.size).toList()
        history = turns.subList(0, firstTrailingTool).toList()
      }
      TurnRole.ASSISTANT -> invalid("The last message must have role 'user' or 'tool'.", "messages")
    }

    return ParsedChatRequest(
      model = req.model,
      systemPrompt = systemParts.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
      history = history,
      current = current,
      temperature = req.temperature,
      topP = req.topP,
      topK = req.topK,
      maxTokens = maxTokens,
      stop = parseStop(req.stop),
      seed = req.seed,
      stream = req.stream,
      includeUsage = req.streamOptions?.includeUsage == true,
      tools = tools,
      toolChoice = toolChoice,
      parallelToolCalls = req.parallelToolCalls ?: true,
      responseFormat = parseResponseFormat(req.responseFormat),
      n = req.n ?: 1,
      enableThinking =
        when (req.reasoningEffort?.lowercase()) {
          null -> null
          "none",
          "minimal" -> false
          "low",
          "medium",
          "high" -> true
          else -> invalid("Unsupported 'reasoning_effort' value '${req.reasoningEffort}'.", "reasoning_effort")
        },
    )
  }

  private data class Content(val text: String, val images: List<ByteArray>, val audios: List<EncodedAudio>)

  private fun parseContent(content: JsonElement?, index: Int, textOnly: Boolean = false): Content {
    val param = "messages[$index].content"
    return when (content) {
      null,
      JsonNull -> Content("", emptyList(), emptyList())
      is JsonPrimitive -> Content(content.contentOrNull ?: "", emptyList(), emptyList())
      is JsonArray -> {
        val texts = mutableListOf<String>()
        val images = mutableListOf<ByteArray>()
        val audios = mutableListOf<EncodedAudio>()
        for (part in content) {
          val obj = part as? JsonObject ?: invalid("Content parts must be objects.", param)
          when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> texts.add(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "image_url" -> {
              if (textOnly) invalid("Images are only supported in user messages.", param)
              val url =
                obj["image_url"]?.let { if (it is JsonObject) it["url"] else it }?.jsonPrimitive?.contentOrNull
                  ?: invalid("'image_url.url' is required.", param)
              images.add(decodeDataUri(url, param))
            }
            "input_audio" -> {
              if (textOnly) invalid("Audio is only supported in user messages.", param)
              val audio = obj["input_audio"]?.jsonObject ?: invalid("'input_audio' is required.", param)
              val data = audio["data"]?.jsonPrimitive?.contentOrNull ?: invalid("'input_audio.data' is required.", param)
              val format = audio["format"]?.jsonPrimitive?.contentOrNull ?: "wav"
              audios.add(EncodedAudio(decodeBase64(data, param), format))
            }
            else -> invalid("Unsupported content part type '$type'.", param)
          }
        }
        Content(texts.joinToString("\n"), images, audios)
      }
      else -> invalid("'content' must be a string or an array of content parts.", param)
    }
  }

  private fun decodeDataUri(url: String, param: String): ByteArray {
    val match =
      DATA_URI_REGEX.matchEntire(url)
        ?: invalid(
          "Only base64 data URIs are supported for images (data:image/...;base64,...). Remote URLs are not fetched.",
          param,
          code = "unsupported_image_url",
        )
    return decodeBase64(match.groupValues[3], param)
  }

  private fun decodeBase64(data: String, param: String): ByteArray =
    try {
      Base64.getMimeDecoder().decode(data)
    } catch (e: IllegalArgumentException) {
      invalid("Invalid base64 data.", param)
    }

  private fun parseTools(tools: List<ToolDto>?): List<FunctionDefinition> =
    tools.orEmpty().mapIndexed { i, tool ->
      if (tool.type != "function") invalid("Only tools of type 'function' are supported.", "tools[$i].type")
      if (!TOOL_NAME_REGEX.matches(tool.function.name)) {
        invalid("Tool names must match ^[a-zA-Z0-9_-]{1,64}$.", "tools[$i].function.name")
      }
      tool.function
    }

  private fun parseToolChoice(choice: JsonElement?, tools: List<FunctionDefinition>): ToolChoice =
    when (choice) {
      null,
      JsonNull -> ToolChoice.Auto
      is JsonPrimitive ->
        when (choice.contentOrNull) {
          "auto" -> ToolChoice.Auto
          "none" -> ToolChoice.None
          "required" -> {
            if (tools.isEmpty()) invalid("'tool_choice' is 'required' but no tools were given.", "tool_choice")
            ToolChoice.Required
          }
          else -> invalid("Unsupported 'tool_choice' value.", "tool_choice")
        }
      is JsonObject -> {
        val name =
          (choice["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: invalid("'tool_choice.function.name' is required.", "tool_choice")
        if (tools.none { it.name == name }) invalid("'tool_choice' names unknown tool '$name'.", "tool_choice")
        ToolChoice.Named(name)
      }
      else -> invalid("Unsupported 'tool_choice' value.", "tool_choice")
    }

  private fun parseStop(stop: JsonElement?): List<String> {
    val list =
      when (stop) {
        null,
        JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(stop.contentOrNull)
        is JsonArray -> stop.map { it.jsonPrimitive.contentOrNull ?: invalid("'stop' entries must be strings.", "stop") }
        else -> invalid("'stop' must be a string or an array of strings.", "stop")
      }.filter { it.isNotEmpty() }
    if (list.size > MAX_STOP_SEQUENCES) invalid("At most $MAX_STOP_SEQUENCES stop sequences are supported.", "stop")
    return list
  }

  private fun parseResponseFormat(format: JsonObject?): ResponseFormatSpec {
    if (format == null) return ResponseFormatSpec.Text
    return when (format["type"]?.jsonPrimitive?.contentOrNull) {
      null,
      "text" -> ResponseFormatSpec.Text
      "json_object" -> ResponseFormatSpec.JsonObject
      "json_schema" -> {
        val schema =
          (format["json_schema"] as? JsonObject)?.get("schema") as? JsonObject
            ?: invalid("'response_format.json_schema.schema' is required.", "response_format")
        ResponseFormatSpec.JsonSchema(schema.toString())
      }
      else -> invalid("Unsupported 'response_format.type'.", "response_format")
    }
  }

  private fun invalid(message: String, param: String?, code: String? = null): Nothing =
    throw OpenAiException(400, message, param = param, code = code)
}
