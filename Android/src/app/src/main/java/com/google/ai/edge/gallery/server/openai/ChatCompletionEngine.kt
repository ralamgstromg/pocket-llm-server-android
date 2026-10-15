package com.google.ai.edge.gallery.server.openai

import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PocketNodeChatEngine"

/** Longest a single inference may run before it is cancelled (504). */
private const val ENGINE_TIMEOUT_MS = 10L * 60 * 1000

/**
 * Text is emitted with this lag so a loop (the model re-writing its own answer) can be detected
 * and cut before the repeated text reaches the client.
 */
private const val REPEAT_WINDOW = 160

/** Thrown inside the collector to stop generation (end-of-turn marker or repeated answer). */
private class RepetitionDetected : RuntimeException()

/** Events produced while answering one chat completion request. */
sealed interface ChatEvent {
  data class Delta(val text: String, val reasoning: String?) : ChatEvent

  data class ToolCalls(val calls: List<Pair<String, String>>) : ChatEvent
}

/** Token counts for one request, exact when the runtime reports them. */
data class TokenCounts(val prompt: Int?, val completion: Int?)

/**
 * Runs one OpenAI chat completion on a loaded LiteRT-LM model.
 *
 * Every request gets its own short-lived [Conversation] built from the full message history, so
 * the API is stateless and does not touch the conversation used by the in-app chat screen. The
 * caller must hold the per-model lock.
 */
object ChatCompletionEngine {
  /**
   * A tool that only describes itself. Tool calls are returned to the API client, which executes
   * them, so [execute] is never used (automatic tool calling is off).
   */
  private class ClientSideTool(private val function: FunctionDefinition) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = LiteRtLmMapper.toolDescriptionJson(function)

    override fun execute(paramsJsonString: String): String =
      """{"error":"Tool '${function.name}' is executed by the API client."}"""
  }

  @OptIn(ExperimentalApi::class)
  suspend fun run(
    model: Model,
    req: ParsedChatRequest,
    decodeAudio: (EncodedAudio) -> ByteArray,
    onEvent: suspend (ChatEvent) -> Unit,
  ): TokenCounts {
    val instance =
      model.instance as? LlmModelInstance
        ?: throw OpenAiException(503, "Model '${model.name}' is not loaded.", code = "model_not_loaded")

    val isNpu =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label) ==
        Accelerator.NPU.label
    val sampler =
      if (isNpu) {
        null
      } else {
        SamplerConfig(
          topK = req.topK ?: model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK),
          // top_p=0 (OpenAI "greedy") is clamped: the sampler needs a positive value.
          topP = (req.topP ?: model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP).toDouble())
            .coerceAtLeast(1e-6),
          temperature =
            req.temperature
              ?: model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE).toDouble(),
          seed = req.seed ?: 0,
        )
      }

    var system = req.systemPrompt
    if (req.toolChoice == ToolChoice.Required || req.toolChoice is ToolChoice.Named) {
      val hint =
        if (req.toolChoice is ToolChoice.Named) "You must call the tool '${req.toolChoice.name}'."
        else "You must call one of the provided tools."
      system = listOfNotNull(system, hint).joinToString("\n\n")
    }
    if (req.tools.isNotEmpty() && ToolPrompt.needsHint(model.toolFormat)) {
      system = listOfNotNull(system, ToolPrompt.hint(req.tools, model.toolFormat)).joinToString("\n\n")
    }
    val maxTokens = req.maxTokens ?: model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val thinking = req.enableThinking?.let { ThinkingConfig(it) }
    val responseFormat = LiteRtLmMapper.responseFormat(req.responseFormat)

    val config =
      ConversationConfig(
        systemInstruction = system?.let { Contents.of(it) },
        initialMessages = LiteRtLmMapper.toMessages(req.history, decodeAudio),
        tools = req.tools.map { tool(ClientSideTool(it)) },
        samplerConfig = sampler,
        automaticToolCalling = false,
        // Without a cap some models never emit end-of-turn and keep repeating the answer.
        maxOutputToken = maxTokens,
        thinkingConfig = thinking,
        enableResponseFormat = responseFormat != null,
      )
    val message = LiteRtLmMapper.toMessage(req.current, decodeAudio)

    val constrained = req.tools.isNotEmpty() && req.toolChoice != ToolChoice.Auto
    val (conversation, borrowedUiConversation) = openConversation(model, instance, config, constrained)
    // End-of-turn markers written as text (and client stops) end generation; otherwise the model
    // keeps writing new turns, repeating its answer until the token cap.
    val stops = (req.stop + StopSequenceFilter.DEFAULT_STOPS).filter { it.isNotEmpty() }.distinct()
    val maxStop = stops.maxOfOrNull { it.length } ?: 0
    val produced = StringBuilder()
    var emitted = 0
    try {
      val finished =
        withTimeoutOrNull(ENGINE_TIMEOUT_MS) {
          try {
            conversation
              .sendMessageAsync(message, emptyMap(), null, null, null, maxTokens, thinking, responseFormat)
              // LiteRT-LM's callbackFlow uses trySend with a bounded buffer; never drop deltas.
              .buffer(Channel.UNLIMITED)
              .collect { partial ->
                val out = LiteRtLmMapper.output(partial)
                val scanFrom = (produced.length - maxStop).coerceAtLeast(0)
                produced.append(out.text)
                val stopAt = stops.mapNotNull { st -> produced.indexOf(st, scanFrom).takeIf { it >= 0 } }.minOrNull()
                if (stopAt != null) {
                  produced.setLength(stopAt)
                  throw RepetitionDetected()
                }
                val repeatAt = repetitionStart(produced)
                if (repeatAt >= 0) {
                  produced.setLength(repeatAt)
                  throw RepetitionDetected()
                }
                val ready = (produced.length - REPEAT_WINDOW).coerceAtLeast(emitted)
                val text = produced.substring(emitted, ready)
                emitted = ready
                if (text.isNotEmpty() || out.reasoning != null) onEvent(ChatEvent.Delta(text, out.reasoning))
                if (out.toolCalls.isNotEmpty()) onEvent(ChatEvent.ToolCalls(out.toolCalls))
              }
          } catch (e: RepetitionDetected) {
            Log.i(TAG, "Generation stopped early for '${model.name}' (end-of-turn marker or repetition).")
            runCatching { conversation.cancelProcess() }
          }
          true
        }
      if (finished == null) {
        runCatching { conversation.cancelProcess() }
        throw OpenAiException(504, "Inference timed out after ${ENGINE_TIMEOUT_MS / 60_000} minutes.", code = "inference_timeout")
      }
      if (produced.length > emitted) onEvent(ChatEvent.Delta(produced.substring(emitted), null))
      return tokenCounts(conversation)
    } catch (e: Throwable) {
      runCatching { conversation.cancelProcess() }
      throw e
    } finally {
      runCatching { conversation.close() }
      if (borrowedUiConversation) restoreUiConversation(model, instance)
    }
  }

  /**
   * Creates the request conversation. If the engine refuses a second live conversation, the
   * in-app conversation is closed first and recreated afterwards (second value = true).
   */
  @OptIn(ExperimentalApi::class)
  private fun openConversation(
    model: Model,
    instance: LlmModelInstance,
    config: ConversationConfig,
    constrained: Boolean,
  ): Pair<Conversation, Boolean> {
    synchronized(ExperimentalFlags) {
      ExperimentalFlags.enableConversationConstrainedDecoding = constrained
      try {
        return try {
          Pair(instance.engine.createConversation(config), false)
        } catch (e: Exception) {
          Log.w(TAG, "Could not open a parallel conversation (${e.message}); borrowing the UI conversation.")
          runCatching { instance.conversation.cancelProcess() }
          runCatching { instance.conversation.close() }
          try {
            Pair(instance.engine.createConversation(config), true)
          } catch (e2: Exception) {
            restoreUiConversation(model, instance)
            throw e2
          }
        }
      } finally {
        ExperimentalFlags.enableConversationConstrainedDecoding = false
      }
    }
  }

  /** Recreates the in-app conversation with the configuration it had before it was borrowed. */
  private fun restoreUiConversation(model: Model, instance: LlmModelInstance) {
    Log.w(TAG, "Recreating the in-app conversation for '${model.name}' after an API request borrowed it.")
    LlmChatModelHelper.resetConversation(
      model = model,
      supportImage = model.llmSupportImage,
      supportAudio = model.llmSupportAudio,
      systemInstruction = instance.systemInstruction,
      tools = instance.tools,
      enableConversationConstrainedDecoding = instance.enableConversationConstrainedDecoding,
    )
  }

  /**
   * Returns where a repeated block starts when the last [REPEAT_WINDOW] chars already appeared
   * earlier (non-overlapping), i.e. the model is re-writing previous output; -1 otherwise.
   */
  private fun repetitionStart(text: StringBuilder): Int {
    if (text.length < REPEAT_WINDOW * 2) return -1
    val tailStart = text.length - REPEAT_WINDOW
    val tail = text.substring(tailStart)
    if (tail.isBlank()) return -1
    val prev = text.lastIndexOf(tail, tailStart - REPEAT_WINDOW)
    return if (prev >= 0) tailStart else -1
  }

  @OptIn(ExperimentalApi::class)
  private fun tokenCounts(conversation: Conversation): TokenCounts =
    try {
      // Without benchmark mode the runtime may report zeros; treat those as unknown.
      val info = conversation.getBenchmarkInfo()
      TokenCounts(info.lastPrefillTokenCount.takeIf { it > 0 }, info.lastDecodeTokenCount.takeIf { it > 0 })
    } catch (e: Exception) {
      TokenCounts(null, null)
    }
}
