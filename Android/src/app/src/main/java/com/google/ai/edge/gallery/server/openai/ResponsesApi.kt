package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** `/v1/responses` request (the subset a local model can honour). */
@Serializable
data class ResponsesRequest(
  val model: String = "",
  /** A string, or an array of input items (messages, function calls and their outputs). */
  val input: JsonElement? = null,
  val instructions: String? = null,
  val temperature: Double? = null,
  @SerialName("top_p") val topP: Double? = null,
  @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
  val stream: Boolean = false,
  val tools: List<JsonObject>? = null,
  @SerialName("tool_choice") val toolChoice: JsonElement? = null,
  @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
  val reasoning: JsonObject? = null,
  val text: JsonObject? = null,
  val store: Boolean? = null,
)

/** Converts Responses API requests to chat requests, and chat outcomes back to Responses objects. */
object ResponsesApi {
  private fun invalid(message: String, param: String): Nothing = throw OpenAiException(400, message, param = param)

  private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull

  fun toChatRequest(req: ResponsesRequest): ChatCompletionRequest {
    val messages = mutableListOf<ChatMessageDto>()
    req.instructions?.takeIf { it.isNotBlank() }?.let { messages.add(ChatMessageDto("system", JsonPrimitive(it))) }
    when (val input = req.input) {
      null,
      JsonNull -> invalid("'input' is required.", "input")
      is JsonPrimitive -> messages.add(ChatMessageDto("user", input))
      is JsonArray -> input.forEachIndexed { i, item -> convertItem(item, i, messages) }
      else -> invalid("'input' must be a string or an array.", "input")
    }
    val tools =
      req.tools?.mapIndexed { i, t ->
        if (str(t, "type") != "function") invalid("Only tools of type 'function' are supported.", "tools[$i].type")
        // Responses tools are flat; chat tools nest the definition under "function".
        val fn = (t["function"] as? JsonObject) ?: t
        ToolDto(
          function =
            FunctionDefinition(
              name = str(fn, "name") ?: invalid("Tool 'name' is required.", "tools[$i].name"),
              description = str(fn, "description"),
              parameters = fn["parameters"] as? JsonObject,
            )
        )
      }
    val toolChoice =
      when (val c = req.toolChoice) {
        is JsonObject -> {
          // Responses: {"type":"function","name":"x"}; chat: {"type":"function","function":{"name":"x"}}.
          val name = str(c, "name") ?: (c["function"] as? JsonObject)?.let { str(it, "name") } ?: invalid("'tool_choice.name' is required.", "tool_choice")
          buildJsonObject { put("type", "function"); putJsonObject("function") { put("name", name) } }
        }
        else -> c
      }
    val format = req.text?.get("format") as? JsonObject
    return ChatCompletionRequest(
      model = req.model,
      messages = messages,
      temperature = req.temperature,
      topP = req.topP,
      maxCompletionTokens = req.maxOutputTokens,
      stream = false,
      tools = tools,
      toolChoice = toolChoice,
      parallelToolCalls = req.parallelToolCalls,
      responseFormat = format?.let { chatFormat(it) },
      reasoningEffort = req.reasoning?.let { str(it, "effort") },
    )
  }

  /** Responses `text.format` -> chat `response_format`. */
  private fun chatFormat(format: JsonObject): JsonObject? =
    when (str(format, "type")) {
      "json_object" -> format
      "json_schema" ->
        buildJsonObject {
          put("type", "json_schema")
          putJsonObject("json_schema") { format["schema"]?.let { put("schema", it) } }
        }
      else -> null
    }

  private fun convertItem(item: JsonElement, index: Int, out: MutableList<ChatMessageDto>) {
    val o = item as? JsonObject ?: invalid("Input items must be objects.", "input[$index]")
    when (str(o, "type") ?: if (o.containsKey("role")) "message" else null) {
      "message" -> {
        val role = str(o, "role") ?: "user"
        out.add(ChatMessageDto(role, convertContent(o["content"], index)))
      }
      "function_call" -> {
        val call =
          ToolCallDto(
            id = str(o, "call_id") ?: str(o, "id"),
            function = FunctionCallDto(str(o, "name"), str(o, "arguments") ?: "{}"),
          )
        val last = out.lastOrNull()
        if (last != null && last.role == "assistant" && last.content.let { it == null || it == JsonNull }) {
          out[out.lastIndex] = last.copy(toolCalls = last.toolCalls.orEmpty() + call)
        } else {
          out.add(ChatMessageDto("assistant", null, toolCalls = listOf(call)))
        }
      }
      "function_call_output" ->
        out.add(
          ChatMessageDto(
            "tool",
            JsonPrimitive(str(o, "output") ?: ""),
            toolCallId = str(o, "call_id") ?: invalid("'call_id' is required.", "input[$index].call_id"),
          )
        )
      else -> invalid("Unsupported input item type '${str(o, "type")}'.", "input[$index].type")
    }
  }

  /** Maps `input_text`/`output_text`/`input_image` parts to chat content parts. */
  private fun convertContent(content: JsonElement?, index: Int): JsonElement? =
    when (content) {
      is JsonArray ->
        buildJsonArray {
          content.forEach { part ->
            val p = part as? JsonObject ?: invalid("Content parts must be objects.", "input[$index].content")
            when (str(p, "type")) {
              "input_text",
              "output_text",
              "text" -> add(buildJsonObject { put("type", "text"); put("text", str(p, "text") ?: "") })
              "input_image" ->
                add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", str(p, "image_url") ?: "") } })
              "input_audio" -> add(p)
              else -> invalid("Unsupported content part type '${str(p, "type")}'.", "input[$index].content")
            }
          }
        }
      else -> content
    }

  private fun usageJson(u: Usage) =
    buildJsonObject {
      put("input_tokens", u.promptTokens)
      put("output_tokens", u.completionTokens)
      put("total_tokens", u.totalTokens)
    }

  fun messageItem(id: String, text: String, status: String): JsonObject =
    buildJsonObject {
      put("type", "message")
      put("id", id)
      put("status", status)
      put("role", "assistant")
      putJsonArray("content") {
        add(
          buildJsonObject {
            put("type", "output_text")
            put("text", text)
            putJsonArray("annotations") {}
          }
        )
      }
    }

  fun functionCallItem(id: String, call: ParsedToolCall, status: String): JsonObject =
    buildJsonObject {
      put("type", "function_call")
      put("id", id)
      put("status", status)
      put("call_id", call.id)
      put("name", call.name)
      put("arguments", call.argumentsJson)
    }

  /** The `response` object. [output] holds the finished output items. */
  fun response(id: String, created: Long, model: String, status: String, output: List<JsonObject>, usage: Usage?, incompleteReason: String? = null): JsonObject =
    buildJsonObject {
      put("id", id)
      put("object", "response")
      put("created_at", created)
      put("status", status)
      put("model", model)
      putJsonArray("output") { output.forEach { add(it) } }
      if (incompleteReason != null) putJsonObject("incomplete_details") { put("reason", incompleteReason) }
      if (usage != null) put("usage", usageJson(usage))
    }

  /** Output items for a finished chat outcome, ids derived from [responseId]. */
  fun outputItems(responseId: String, content: String, toolCalls: List<ParsedToolCall>): List<JsonObject> {
    val items = mutableListOf<JsonObject>()
    if (content.isNotEmpty() || toolCalls.isEmpty()) items.add(messageItem("msg_$responseId", content, "completed"))
    toolCalls.forEachIndexed { i, c -> items.add(functionCallItem("fc_${responseId}_$i", c, "completed")) }
    return items
  }

  /** One server-sent event in the Responses format: `event:` line plus `data:` payload. */
  fun sse(type: String, seq: Int, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): String {
    val payload = buildJsonObject {
      put("type", type)
      put("sequence_number", seq)
      build()
    }
    return "event: $type\ndata: ${OpenAiJson.encodeToString(JsonObject.serializer(), payload)}\n\n"
  }
}
