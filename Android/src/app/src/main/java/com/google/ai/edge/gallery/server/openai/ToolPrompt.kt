package com.google.ai.edge.gallery.server.openai

/**
 * Tells a model how to write tool calls when its chat template does not do it already, so the
 * server can return them as OpenAI `tool_calls` whatever the model is.
 */
object ToolPrompt {
  /** Formats whose chat template already explains the call syntax to the model. */
  private val SELF_DESCRIBING = setOf("native", "hermes")

  fun needsHint(toolFormat: String) = toolFormat.lowercase() !in SELF_DESCRIBING

  fun hint(tools: List<FunctionDefinition>, toolFormat: String): String {
    val specs =
      tools.joinToString("\n") { t ->
        val params = t.parameters?.toString() ?: """{"type":"object","properties":{}}"""
        """{"name":"${t.name}","description":${kotlinx.serialization.json.JsonPrimitive(t.description.orEmpty())},"parameters":$params}"""
      }
    val example =
      when (toolFormat.lowercase()) {
        "pythonic" -> "<|tool_call_start|>[function_name(arg1=\"value\", arg2=2)]<|tool_call_end|>"
        "mistral" -> "[TOOL_CALLS][{\"name\": \"function_name\", \"arguments\": {\"arg1\": \"value\"}}]"
        else -> "<tool_call>\n{\"name\": \"function_name\", \"arguments\": {\"arg1\": \"value\"}}\n</tool_call>"
      }
    return "You can call these functions (JSON Schema):\n$specs\n\n" +
      "To call a function, answer only with the call in exactly this format, with no other text:\n$example\n" +
      "Use only the functions listed. Arguments must be valid JSON that follows the schema."
  }
}
