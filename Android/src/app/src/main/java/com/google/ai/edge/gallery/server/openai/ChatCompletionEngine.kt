package com.google.ai.edge.gallery.server.openai

import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
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

private const val TAG = "PocketNodeChatEngine"

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
    val thinking = req.enableThinking?.let { ThinkingConfig(it) }
    val responseFormat = LiteRtLmMapper.responseFormat(req.responseFormat)

    val config =
      ConversationConfig(
        systemInstruction = system?.let { Contents.of(it) },
        initialMessages = LiteRtLmMapper.toMessages(req.history, decodeAudio),
        tools = req.tools.map { tool(ClientSideTool(it)) },
        samplerConfig = sampler,
        automaticToolCalling = false,
        maxOutputToken = req.maxTokens,
        thinkingConfig = thinking,
        enableResponseFormat = responseFormat != null,
      )
    val message = LiteRtLmMapper.toMessage(req.current, decodeAudio)

    val constrained = req.tools.isNotEmpty() && req.toolChoice != ToolChoice.Auto
    val (conversation, borrowedUiConversation) = openConversation(model, instance, config, constrained)
    try {
      conversation
        .sendMessageAsync(
          message,
          emptyMap(),
          null,
          null,
          null,
          req.maxTokens,
          thinking,
          responseFormat,
        )
        // LiteRT-LM's callbackFlow uses trySend with a bounded buffer; never drop deltas.
        .buffer(Channel.UNLIMITED)
        .collect { partial ->
          val out = LiteRtLmMapper.output(partial)
          if (out.text.isNotEmpty() || out.reasoning != null) onEvent(ChatEvent.Delta(out.text, out.reasoning))
          if (out.toolCalls.isNotEmpty()) onEvent(ChatEvent.ToolCalls(out.toolCalls))
        }
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
