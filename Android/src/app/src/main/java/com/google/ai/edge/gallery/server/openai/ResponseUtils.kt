package com.google.ai.edge.gallery.server.openai

import java.security.MessageDigest

/**
 * Holds back text that could be the start of a stop sequence and truncates the output when a stop
 * sequence is found. Works on streamed deltas.
 */
class StopSequenceFilter(private val stops: List<String>) {
  private val effectiveStops = (stops + DEFAULT_STOPS).distinct().filter { it.isNotEmpty() }
  private val buffer = StringBuilder()
  private val maxStopLength = effectiveStops.maxOfOrNull { it.length } ?: 0

  var stopped = false
    private set

  /** Feeds a delta and returns the text that is safe to emit. */
  fun feed(delta: String): String {
    if (stopped) return ""
    buffer.append(delta)
    val hit = effectiveStops.mapNotNull { s -> buffer.indexOf(s).takeIf { it >= 0 } }.minOrNull()
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

  companion object {
    val DEFAULT_STOPS = listOf(
      "<|im_end|>",
      "<|endoftext|>",
      "<end_of_turn>",
      "<|im_start|>",
      "<|user|>",
      "<|assistant|>",
      "</s>",
      "<eos>",
    )
  }
}

object ResponseUtils {
  private val THINK_REGEX = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)

  /** Moves inline `<think>...</think>` blocks out of the content. */
  fun splitThinking(text: String): Pair<String, String?> {
    // A leading "...</think>" without an opener means the chat template pre-filled "<think>".
    val close = text.indexOf("</think>")
    val open = text.indexOf("<think>")
    if (close >= 0 && (open < 0 || close < open)) {
      val (rest, more) = splitThinking(text.substring(close + "</think>".length))
      val lead = text.substring(0, close).trim()
      val all = listOfNotNull(lead.ifEmpty { null }, more).joinToString("\n")
      return Pair(rest.trimStart(), all.ifEmpty { null })
    }
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

  fun choice(index: Int, content: String, reasoning: String?, toolCalls: List<ParsedToolCall>, finishReason: String) =
    ChatChoice(
      index = index,
      message =
        AssistantMessage(
          content = if (toolCalls.isNotEmpty() && content.isEmpty()) null else content,
          reasoningContent = reasoning,
          toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.let { toolCallDtos(it) },
        ),
      finishReason = finishReason,
    )

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
      choices = listOf(choice(0, content, reasoning, toolCalls, finishReason)),
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

  /** OpenAI finish_reason; [completionTokens] may be an estimate. */
  fun finishReason(hasToolCalls: Boolean, stopped: Boolean, maxTokens: Int?, completionTokens: Int): String =
    when {
      hasToolCalls -> "tool_calls"
      stopped -> "stop"
      maxTokens != null && completionTokens >= maxTokens -> "length"
      else -> "stop"
    }

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
