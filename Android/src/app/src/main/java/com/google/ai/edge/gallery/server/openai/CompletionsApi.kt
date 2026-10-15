package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Legacy `/v1/completions` request. */
@Serializable
data class CompletionRequest(
  val model: String = "",
  val prompt: JsonElement? = null,
  val suffix: String? = null,
  val temperature: Double? = null,
  @SerialName("top_p") val topP: Double? = null,
  @SerialName("top_k") val topK: Int? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  val stop: JsonElement? = null,
  val seed: Int? = null,
  val n: Int? = null,
  val stream: Boolean = false,
  @SerialName("stream_options") val streamOptions: StreamOptions? = null,
  val echo: Boolean? = null,
  val logprobs: Int? = null,
  @SerialName("best_of") val bestOf: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CompletionChoice(
  val text: String,
  val index: Int = 0,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) val logprobs: String? = null,
  @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("finish_reason") val finishReason: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CompletionResponse(
  val id: String,
  @EncodeDefault val `object`: String = "text_completion",
  val created: Long,
  val model: String,
  val choices: List<CompletionChoice>,
  val usage: Usage? = null,
)

object CompletionsApi {
  private fun invalid(message: String, param: String): Nothing = throw OpenAiException(400, message, param = param)

  /** The prompt as plain text. Only one prompt per request is supported. */
  fun promptText(prompt: JsonElement?): String =
    when (prompt) {
      null -> invalid("'prompt' is required.", "prompt")
      is JsonPrimitive ->
        if (prompt.isString) prompt.content else invalid("'prompt' must be a string.", "prompt")
      is JsonArray -> {
        val items = prompt.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
        if (items.size != 1 || items[0] == null) invalid("Only a single string prompt is supported.", "prompt")
        items[0]!!
      }
      else -> invalid("'prompt' must be a string.", "prompt")
    }

  /** Maps a legacy completion request to the chat request the rest of the server understands. */
  fun toChatRequest(req: CompletionRequest): Pair<ChatCompletionRequest, String> {
    val prompt = promptText(req.prompt)
    if (!req.suffix.isNullOrEmpty()) invalid("'suffix' is not supported.", "suffix")
    if ((req.logprobs ?: 0) > 0) invalid("'logprobs' is not supported.", "logprobs")
    if (req.bestOf != null && req.bestOf > 1) invalid("'best_of' is not supported.", "best_of")
    val chat =
      ChatCompletionRequest(
        model = req.model,
        messages =
          listOf(ChatMessageDto(role = "user", content = JsonPrimitive(prompt))),
        temperature = req.temperature,
        topP = req.topP,
        topK = req.topK,
        maxTokens = req.maxTokens,
        stop = req.stop,
        seed = req.seed,
        n = req.n,
        stream = req.stream,
        streamOptions = req.streamOptions,
      )
    return Pair(chat, prompt)
  }
}
