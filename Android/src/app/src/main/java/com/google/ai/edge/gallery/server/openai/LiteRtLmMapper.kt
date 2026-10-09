package com.google.ai.edge.gallery.server.openai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Text, reasoning and tool calls extracted from a LiteRT-LM message. */
data class ModelOutput(
  val text: String,
  val reasoning: String?,
  /** (name, arguments as a JSON string). */
  val toolCalls: List<Pair<String, String>>,
)

/** Converts between the parsed OpenAI request model and LiteRT-LM types. */
object LiteRtLmMapper {
  private val EMPTY_OBJECT_SCHEMA = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(emptyMap()))
  }

  /** The OpenAPI function description that LiteRT-LM expects from an `OpenApiTool`. */
  fun toolDescriptionJson(function: FunctionDefinition): String =
    buildJsonObject {
        put("name", function.name)
        function.description?.let { put("description", it) }
        put("parameters", function.parameters ?: EMPTY_OBJECT_SCHEMA)
      }
      .toString()

  /** Converts history turns, one message per turn (consecutive tool turns are merged). */
  fun toMessages(turns: List<ParsedTurn>, decodeAudio: (EncodedAudio) -> ByteArray): List<Message> {
    val messages = mutableListOf<Message>()
    var i = 0
    while (i < turns.size) {
      if (turns[i].role == TurnRole.TOOL) {
        var j = i
        while (j < turns.size && turns[j].role == TurnRole.TOOL) j++
        messages.add(toMessage(turns.subList(i, j), decodeAudio))
        i = j
      } else {
        messages.add(toMessage(listOf(turns[i]), decodeAudio))
        i++
      }
    }
    return messages
  }

  /** Converts one user/assistant turn, or a group of tool-result turns, into a message. */
  fun toMessage(turns: List<ParsedTurn>, decodeAudio: (EncodedAudio) -> ByteArray): Message {
    val first = turns.first()
    return when (first.role) {
      TurnRole.USER -> {
        val parts = mutableListOf<Content>()
        first.images.forEach { parts.add(Content.ImageBytes(it)) }
        first.audios.forEach { parts.add(Content.AudioBytes(decodeAudio(it))) }
        // Text goes last so the final token belongs to the text prompt.
        if (first.text.isNotEmpty() || parts.isEmpty()) parts.add(Content.Text(first.text))
        Message.user(Contents.of(parts))
      }
      TurnRole.ASSISTANT ->
        Message.model(
          Contents.of(if (first.text.isEmpty()) emptyList() else listOf(Content.Text(first.text))),
          first.toolCalls.map { ToolCall(it.name, argumentsToMap(it.argumentsJson)) },
          emptyMap(),
        )
      TurnRole.TOOL ->
        Message.tool(Contents.of(turns.map { Content.ToolResponse(it.toolName ?: "", toolResponseValue(it.text)) }))
    }
  }

  fun output(message: Message): ModelOutput =
    ModelOutput(
      text = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text },
      reasoning = message.channels["thought"]?.takeIf { it.isNotEmpty() },
      toolCalls = message.toolCalls.map { Pair(it.name, toJsonElement(it.arguments).toString()) },
    )

  fun responseFormat(spec: ResponseFormatSpec): ResponseFormat? =
    when (spec) {
      ResponseFormatSpec.Text -> null
      ResponseFormatSpec.JsonObject -> ResponseFormat.json(EMPTY_OBJECT_SCHEMA.toString())
      is ResponseFormatSpec.JsonSchema -> ResponseFormat.json(spec.schemaJson)
    }

  /** Parses tool-call arguments; invalid JSON is passed as a single "input" argument. */
  fun argumentsToMap(json: String): Map<String, Any?> =
    when (val parsed = parseJsonOrNull(json)) {
      is JsonObject -> parsed.mapValues { fromJsonElement(it.value) }
      else -> mapOf("input" to json)
    }

  private fun toolResponseValue(text: String): Any =
    when (val parsed = parseJsonOrNull(text)) {
      is JsonObject,
      is JsonArray -> fromJsonElement(parsed) ?: text
      else -> text
    }

  private fun parseJsonOrNull(text: String): JsonElement? =
    try {
      OpenAiJson.parseToJsonElement(text)
    } catch (e: Exception) {
      null
    }

  private fun fromJsonElement(e: JsonElement): Any? =
    when (e) {
      JsonNull -> null
      is JsonObject -> e.mapValues { fromJsonElement(it.value) }
      is JsonArray -> e.map { fromJsonElement(it) }
      is JsonPrimitive ->
        if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    }

  private fun toJsonElement(value: Any?): JsonElement =
    when (value) {
      null -> JsonNull
      is JsonElement -> value
      is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJsonElement(it.value) })
      is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
      is Array<*> -> JsonArray(value.map { toJsonElement(it) })
      is Boolean -> JsonPrimitive(value)
      is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
      is Float -> toJsonElement(value.toDouble())
      is Number -> JsonPrimitive(value)
      else -> JsonPrimitive(value.toString())
    }
}
