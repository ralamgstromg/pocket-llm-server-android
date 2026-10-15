package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.openai.ChatEvent
import com.google.ai.edge.gallery.server.openai.ChatMessageDto
import com.google.ai.edge.gallery.server.openai.ChatRequestParser
import com.google.ai.edge.gallery.server.openai.EncodedAudio
import com.google.ai.edge.gallery.server.openai.FunctionCallDto
import com.google.ai.edge.gallery.server.openai.JsonRepair
import com.google.ai.edge.gallery.server.openai.ParsedChatRequest
import com.google.ai.edge.gallery.server.openai.ParsedToolCall
import com.google.ai.edge.gallery.server.openai.ToolCallDto
import com.google.ai.edge.gallery.server.openai.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Runs the multi-turn loop when a request enables server-side skills (`x_pocket_skills`). */
object SkillLoop {
  const val MAX_ROUNDS = 5
  val SKILL_TOOLS = setOf("load_skill", "run_skill_js")

  /**
   * Evaluates skill tool calls produced by the model, appends the tool results, and runs another
   * turn until the model stops or calls a client-side tool (or [MAX_ROUNDS] is reached).
   */
  suspend fun run(
    model: Model,
    initialReq: ParsedChatRequest,
    initialChatMessages: List<ChatMessageDto>,
    executor: SkillExecutor,
    decodeAudio: (EncodedAudio) -> ByteArray,
  ): ChatOutcome {
    var curReq = initialReq
    val messages = initialChatMessages.toMutableList()
    var lastOutcome: ChatOutcome? = null
    var totalUsage = Usage(0, 0, 0)

    for (round in 0 until MAX_ROUNDS) {
      val outcome = ChatRunner.run(model, curReq, decodeAudio, live = false)
      lastOutcome = outcome
      totalUsage = Usage(totalUsage.promptTokens + outcome.usage.promptTokens, totalUsage.completionTokens + outcome.usage.completionTokens, totalUsage.totalTokens + outcome.usage.totalTokens, estimated = if (totalUsage.estimated == true || outcome.usage.estimated == true) true else null)

      val skillCalls = outcome.toolCalls.filter { it.name in SKILL_TOOLS }
      val clientCalls = outcome.toolCalls.filter { it.name !in SKILL_TOOLS }
      // If the model called client tools, or produced no skill calls, we are done.
      if (skillCalls.isEmpty() || clientCalls.isNotEmpty()) {
        return outcome.copy(toolCalls = clientCalls, usage = totalUsage)
      }

      // Record assistant's turn with its tool calls.
      messages.add(
        ChatMessageDto(
          role = "assistant",
          content = if (outcome.content.isNotEmpty()) JsonPrimitive(outcome.content) else null,
          toolCalls = outcome.toolCalls.map { ToolCallDto(id = it.id, function = FunctionCallDto(it.name, it.argumentsJson)) },
        )
      )

      // Execute each skill tool and record the tool messages.
      for (call in skillCalls) {
        val toolResult = executeSkill(call, executor)
        messages.add(ChatMessageDto(role = "tool", content = JsonPrimitive(toolResult.resultJson), toolCallId = call.id))
      }

      // Re-parse with the updated history.
      val chatReq =
        com.google.ai.edge.gallery.server.openai.ChatCompletionRequest(
          model = curReq.model,
          messages = messages,
          temperature = curReq.temperature,
          topP = curReq.topP,
          topK = curReq.topK,
          maxCompletionTokens = curReq.maxTokens,
          stop = null,
          tools = curReq.tools.map { com.google.ai.edge.gallery.server.openai.ToolDto(function = it) },
        )
      curReq = ChatRequestParser.parse(chatReq).copy(systemPrompt = curReq.systemPrompt)
    }
    return lastOutcome ?: ChatOutcome("", null, emptyList(), "stop", totalUsage)
  }

  private suspend fun executeSkill(call: ParsedToolCall, executor: SkillExecutor): SkillToolOutcome {
    val args = (JsonRepair.parse(call.argumentsJson) as? JsonObject) ?: JsonObject(emptyMap())
    fun arg(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (call.name) {
      "load_skill" -> executor.loadSkill(arg("skill_name").ifEmpty { arg("name") })
      "run_skill_js" ->
        executor.runJs(
          name = arg("skill_name").ifEmpty { arg("name") },
          scriptName = arg("script_name").ifEmpty { "index.html" },
          data = (args["data"] as? JsonPrimitive)?.contentOrNull ?: args["data"]?.toString() ?: "{}",
        )
      else -> SkillToolOutcome("""{"error":"Unknown skill tool '${call.name}'"}""", isError = true)
    }
  }
}
