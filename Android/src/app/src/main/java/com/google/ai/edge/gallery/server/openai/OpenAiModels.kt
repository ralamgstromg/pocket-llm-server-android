package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Json configuration shared by the OpenAI-compatible endpoints. */
val OpenAiJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

// ---------------------------------------------------------------------------------------------
// Requests.
// ---------------------------------------------------------------------------------------------

@Serializable
data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatMessageDto> = emptyList(),
  val temperature: Double? = null,
  @SerialName("top_p") val topP: Double? = null,
  /** Non-standard extension, supported by most local servers. */
  @SerialName("top_k") val topK: Int? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
  val stop: JsonElement? = null,
  val seed: Int? = null,
  val n: Int? = null,
  val stream: Boolean = false,
  @SerialName("stream_options") val streamOptions: StreamOptions? = null,
  val tools: List<ToolDto>? = null,
  @SerialName("tool_choice") val toolChoice: JsonElement? = null,
  @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
  @SerialName("response_format") val responseFormat: JsonObject? = null,
  @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = false)

@Serializable
data class ChatMessageDto(
  val role: String,
  /** A string, an array of content parts, or null (assistant messages with tool calls). */
  val content: JsonElement? = null,
  val name: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ToolDto(val type: String = "function", val function: FunctionDefinition)

@Serializable
data class FunctionDefinition(
  val name: String,
  val description: String? = null,
  val parameters: JsonObject? = null,
  val strict: Boolean? = null,
)

@Serializable
data class ToolCallDto(
  /** Only used in streaming deltas. */
  val index: Int? = null,
  val id: String? = null,
  val type: String? = "function",
  val function: FunctionCallDto,
)

@Serializable data class FunctionCallDto(val name: String? = null, val arguments: String? = null)

// ---------------------------------------------------------------------------------------------
// Responses.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionResponse(
  val id: String,
  @EncodeDefault val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<ChatChoice>,
  val usage: Usage? = null,
)

@Serializable
data class ChatChoice(
  val index: Int = 0,
  val message: AssistantMessage,
  @SerialName("finish_reason") val finishReason: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AssistantMessage(
  @EncodeDefault val role: String = "assistant",
  /** Null when the model only returns tool calls, as OpenAI does. */
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val content: String? = null,
  @SerialName("reasoning_content") val reasoningContent: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
data class Usage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
  /** Non-standard: true when the runtime could not report exact token counts. */
  val estimated: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionChunk(
  val id: String,
  @EncodeDefault val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoice>,
  val usage: Usage? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChunkChoice(
  val index: Int = 0,
  val delta: ChunkDelta,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS)
  @SerialName("finish_reason")
  val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
  val role: String? = null,
  val content: String? = null,
  @SerialName("reasoning_content") val reasoningContent: String? = null,
  @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModelObject(
  val id: String,
  @EncodeDefault val `object`: String = "model",
  val created: Long,
  @SerialName("owned_by") val ownedBy: String,
  /** Non-standard: whether the model file is present on the device. */
  val downloaded: Boolean? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModelList(@EncodeDefault val `object`: String = "list", val data: List<ModelObject>)

@Serializable
data class SkillObject(
  val id: String,
  val name: String,
  val description: String,
  val selected: Boolean,
  val builtIn: Boolean,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SkillList(
  @EncodeDefault val `object`: String = "list",
  val data: List<SkillObject>,
)

@Serializable data class TranscriptionResponse(val text: String)

@Serializable data class ErrorResponse(val error: ErrorDetail)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ErrorDetail(
  val message: String,
  val type: String,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val param: String? = null,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val code: String? = null,
)

/** An error that maps to an OpenAI-style error response with the given HTTP status. */
class OpenAiException(
  val status: Int,
  override val message: String,
  val type: String = defaultType(status),
  val param: String? = null,
  val code: String? = null,
) : RuntimeException(message) {
  fun toResponse() = ErrorResponse(ErrorDetail(message, type, param, code))

  companion object {
    fun defaultType(status: Int) =
      when (status) {
        401 -> "authentication_error"
        in 400..499 -> "invalid_request_error"
        503 -> "service_unavailable_error"
        else -> "server_error"
      }
  }
}
