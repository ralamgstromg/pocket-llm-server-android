package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.openai.ChatCompletionEngine
import com.google.ai.edge.gallery.server.openai.ChatEvent
import com.google.ai.edge.gallery.server.openai.EncodedAudio
import com.google.ai.edge.gallery.server.openai.OpenAiException
import com.google.ai.edge.gallery.server.openai.ParsedChatRequest
import com.google.ai.edge.gallery.server.openai.ParsedToolCall
import com.google.ai.edge.gallery.server.openai.ResponseUtils
import com.google.ai.edge.gallery.server.openai.StopSequenceFilter
import com.google.ai.edge.gallery.server.openai.ThinkStreamSplitter
import com.google.ai.edge.gallery.server.openai.TokenCounts
import com.google.ai.edge.gallery.server.openai.ToolCallStreamFilter
import com.google.ai.edge.gallery.server.openai.ToolCallTextParser
import com.google.ai.edge.gallery.server.openai.ToolChoice
import com.google.ai.edge.gallery.server.openai.Usage
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

/** Longest a single inference may run before the request fails with 504. */
const val INFERENCE_TIMEOUT_MS = 10L * 60 * 1000

/** Everything the three text endpoints (chat, completions, responses) need from one inference. */
data class ChatOutcome(
  val content: String,
  val reasoning: String?,
  val toolCalls: List<ParsedToolCall>,
  val finishReason: String,
  val usage: Usage,
)

/** Receives output while it is produced; only used when running live (streaming). */
class ChatSink(
  val onContent: suspend (String) -> Unit = {},
  val onReasoning: suspend (String) -> Unit = {},
  val onToolCall: suspend (index: Int, call: ParsedToolCall) -> Unit = { _, _ -> },
)

/** Runs one request on a loaded model and shapes the result; shared by every text endpoint. */
object ChatRunner {
  fun newCallId() = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}"

  /**
   * Runs [req]. With [live] the [sink] gets content, reasoning and tool calls as they arrive;
   * otherwise only the returned [ChatOutcome] carries the result. The caller holds the model lock.
   */
  suspend fun run(
    model: Model,
    req: ParsedChatRequest,
    decodeAudio: (EncodedAudio) -> ByteArray,
    live: Boolean,
    sink: ChatSink = ChatSink(),
  ): ChatOutcome {
    val outcome =
      withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
        if (live) runLive(model, req, decodeAudio, sink) else runBufferedWithRetry(model, req, decodeAudio)
      }
    return outcome ?: throw OpenAiException(504, "Inference timed out after ${INFERENCE_TIMEOUT_MS / 60_000} minutes.", code = "inference_timeout")
  }

  /** With `tool_choice` required or named, asks once more if the model answered without a call. */
  private suspend fun runBufferedWithRetry(model: Model, req: ParsedChatRequest, decodeAudio: (EncodedAudio) -> ByteArray): ChatOutcome {
    val first = runBuffered(model, req, decodeAudio)
    val mustCall = req.toolChoice == ToolChoice.Required || req.toolChoice is ToolChoice.Named
    return if (mustCall && first.toolCalls.isEmpty()) runBuffered(model, req, decodeAudio) else first
  }

  private suspend fun runBuffered(model: Model, req: ParsedChatRequest, decodeAudio: (EncodedAudio) -> ByteArray): ChatOutcome {
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
    val visible = stopFilter.feed(rawContent) + stopFilter.flush()
    val (content, toolCalls) = resolveToolCalls(req, nativeCalls, visible)
    val allReasoning = listOf(reasoning.toString(), inlineReasoning.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    return ChatOutcome(
      content = content,
      reasoning = allReasoning.ifEmpty { null },
      toolCalls = toolCalls,
      finishReason = finishReason(req, toolCalls, stopFilter.stopped, counts, text.length),
      usage = usage(counts, req, text.length),
    )
  }

  private suspend fun runLive(model: Model, req: ParsedChatRequest, decodeAudio: (EncodedAudio) -> ByteArray, sink: ChatSink): ChatOutcome {
    val stopFilter = StopSequenceFilter(req.stop)
    val thinkSplitter = ThinkStreamSplitter(assumeThinkingFirst = req.enableThinking == true)
    // With tools, only the text that cannot be (the start of) a <tool_call> block streams live.
    val toolFilter = if (req.tools.isNotEmpty()) ToolCallStreamFilter() else null
    val allowed = req.tools.map { it.name }.toSet()
    val content = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ParsedToolCall>()
    var producedChars = 0

    suspend fun emitContent(text: String) {
      if (text.isEmpty()) return
      content.append(text)
      sink.onContent(text)
    }

    suspend fun emitReasoning(text: String) {
      if (text.isEmpty()) return
      reasoning.append(text)
      sink.onReasoning(text)
    }

    suspend fun emitCall(call: ParsedToolCall) {
      if (call.name !in allowed || (!req.parallelToolCalls && toolCalls.isNotEmpty())) return
      toolCalls.add(call)
      sink.onToolCall(toolCalls.size - 1, call)
    }

    suspend fun routeVisible(text: String) {
      if (toolFilter == null) emitContent(text) else emitContent(toolFilter.feed(text))
    }

    val counts =
      ChatCompletionEngine.run(model, req, decodeAudio) { event ->
        when (event) {
          is ChatEvent.Delta -> {
            producedChars += event.text.length
            val (text, inlineReasoning) = thinkSplitter.feed(event.text)
            emitReasoning(event.reasoning.orEmpty() + inlineReasoning)
            routeVisible(stopFilter.feed(text))
          }
          is ChatEvent.ToolCalls ->
            event.calls.forEach { c -> ToolCallTextParser.normalizeArguments(c.second)?.let { emitCall(ParsedToolCall(newCallId(), c.first, it)) } }
        }
      }

    val (thinkTail, reasoningTail) = thinkSplitter.flush()
    emitReasoning(reasoningTail)
    routeVisible(stopFilter.feed(thinkTail) + stopFilter.flush())
    if (toolFilter != null) {
      val held = toolFilter.finish()
      if (held.isNotEmpty()) {
        if (toolCalls.isEmpty()) {
          val (rest, calls) = ToolCallTextParser.parse(held, ::newCallId, allowed)
          emitContent(rest)
          calls.forEach { emitCall(it) }
        } else {
          emitContent(held)
        }
      }
    }
    return ChatOutcome(
      content = content.toString(),
      reasoning = reasoning.toString().ifEmpty { null },
      toolCalls = toolCalls,
      finishReason = finishReason(req, toolCalls, stopFilter.stopped, counts, producedChars),
      usage = usage(counts, req, producedChars),
    )
  }

  /** Native tool calls win; otherwise tool calls written as text are parsed (generic models). */
  fun resolveToolCalls(req: ParsedChatRequest, nativeCalls: List<Pair<String, String>>, content: String): Pair<String, List<ParsedToolCall>> {
    if (req.tools.isEmpty()) return Pair(content, emptyList())
    val allowed = req.tools.map { it.name }.toSet()
    var (rest, calls) =
      if (nativeCalls.isNotEmpty()) {
        // Arguments always reach the client as a valid JSON object string.
        Pair(content, nativeCalls.mapNotNull { c -> ToolCallTextParser.normalizeArguments(c.second)?.let { ParsedToolCall(newCallId(), c.first, it) } })
      } else {
        ToolCallTextParser.parse(content, ::newCallId, allowed)
      }
    calls = calls.filter { it.name in allowed }
    if (!req.parallelToolCalls) calls = calls.take(1)
    return Pair(rest, calls)
  }

  private fun finishReason(req: ParsedChatRequest, toolCalls: List<ParsedToolCall>, stopped: Boolean, counts: TokenCounts, completionChars: Int) =
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
}
