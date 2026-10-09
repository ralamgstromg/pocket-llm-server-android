package com.google.ai.edge.gallery.server.openai

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts tool calls that a model wrote as plain text using the Qwen/Hermes convention:
 * `<tool_call>{"name": "...", "arguments": {...}}</tool_call>`.
 *
 * Used for models whose LiteRT-LM bundle has no native tool-call parser (e.g. generic models).
 */
object ToolCallTextParser {
  private val TOOL_CALL_REGEX = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

  /** Returns the remaining text content and the parsed tool calls. Malformed blocks stay as text. */
  fun parse(text: String, newId: () -> String): Pair<String, List<ParsedToolCall>> {
    val calls = mutableListOf<ParsedToolCall>()
    val remaining =
      TOOL_CALL_REGEX.replace(text) { match ->
        val call = parseCall(match.groupValues[1].trim(), newId)
        if (call == null) {
          match.value
        } else {
          calls.add(call)
          ""
        }
      }
    return Pair(if (calls.isEmpty()) text else remaining.trim(), calls)
  }

  private fun parseCall(body: String, newId: () -> String): ParsedToolCall? {
    val obj =
      try {
        OpenAiJson.parseToJsonElement(body) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return null
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
    val args =
      when (val a = obj["arguments"] ?: obj["parameters"]) {
        null -> "{}"
        is JsonPrimitive -> if (a.isString) a.content else a.toString()
        else -> a.toString()
      }
    return ParsedToolCall(newId(), name, args)
  }
}

/**
 * Holds back text that could be the start of a stop sequence and truncates the output when a stop
 * sequence is found. Works on streamed deltas.
 */
class StopSequenceFilter(private val stops: List<String>) {
  private val buffer = StringBuilder()
  private val maxStopLength = stops.maxOfOrNull { it.length } ?: 0

  var stopped = false
    private set

  /** Feeds a delta and returns the text that is safe to emit. */
  fun feed(delta: String): String {
    if (stopped) return ""
    if (stops.isEmpty()) return delta
    buffer.append(delta)
    val hit = stops.mapNotNull { s -> buffer.indexOf(s).takeIf { it >= 0 } }.minOrNull()
    if (hit != null) {
      stopped = true
      val out = buffer.substring(0, hit)
      buffer.clear()
      return out
    }
    val safe = (buffer.length - (maxStopLength - 1)).coerceAtLeast(0)
    val out = buffer.substring(0, safe)
    buffer.delete(0, safe)
    return out
  }

  /** Returns any text still held back. Call once the model is done. */
  fun flush(): String {
    if (stopped) return ""
    val out = buffer.toString()
    buffer.clear()
    return out
  }
}

object ResponseUtils {
  private val THINK_REGEX = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)

  /** Moves inline `<think>...</think>` blocks out of the content. */
  fun splitThinking(text: String): Pair<String, String?> {
    val reasoning = THINK_REGEX.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    if (!text.contains("<think>")) return Pair(text, null)
    val content = THINK_REGEX.replace(text, "").trim()
    return Pair(content, reasoning.takeIf { it.isNotEmpty() }?.joinToString("\n"))
  }

  fun toolCallDtos(calls: List<ParsedToolCall>, withIndex: Boolean = false): List<ToolCallDto> =
    calls.mapIndexed { i, c ->
      ToolCallDto(
        index = if (withIndex) i else null,
        id = c.id,
        type = "function",
        function = FunctionCallDto(name = c.name, arguments = c.argumentsJson),
      )
    }

  fun completion(
    id: String,
    created: Long,
    model: String,
    content: String,
    reasoning: String?,
    toolCalls: List<ParsedToolCall>,
    finishReason: String,
    usage: Usage?,
  ) =
    ChatCompletionResponse(
      id = id,
      created = created,
      model = model,
      choices =
        listOf(
          ChatChoice(
            message =
              AssistantMessage(
                content = if (toolCalls.isNotEmpty() && content.isEmpty()) null else content,
                reasoningContent = reasoning,
                toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.let { toolCallDtos(it) },
              ),
            finishReason = finishReason,
          )
        ),
      usage = usage,
    )

  fun chunk(
    id: String,
    created: Long,
    model: String,
    delta: ChunkDelta,
    finishReason: String?,
    usage: Usage? = null,
  ) =
    ChatCompletionChunk(
      id = id,
      created = created,
      model = model,
      choices = if (usage != null && finishReason == null && delta == ChunkDelta()) emptyList() else listOf(ChunkChoice(delta = delta, finishReason = finishReason)),
      usage = usage,
    )

  fun sse(chunk: ChatCompletionChunk): String =
    "data: ${OpenAiJson.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"

  const val SSE_DONE = "data: [DONE]\n\n"

  /** Rough token estimate (~4 characters per token) for when the runtime gives no counts. */
  fun estimateTokens(charCount: Int): Int = (charCount + 3) / 4
}

/** Optional bearer-token authentication for the server. */
object ApiKeyAuth {
  /** An empty [configuredKey] disables authentication. */
  fun isAuthorized(header: String?, configuredKey: String): Boolean {
    if (configuredKey.isEmpty()) return true
    if (header == null || !header.startsWith("Bearer ")) return false
    val given = header.removePrefix("Bearer ").trim()
    return MessageDigest.isEqual(given.toByteArray(), configuredKey.toByteArray())
  }
}
