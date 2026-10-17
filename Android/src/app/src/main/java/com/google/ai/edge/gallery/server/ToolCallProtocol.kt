package com.google.ai.edge.gallery.server

import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tool calling for models without native function calling. The server describes the OpenAI tools in
 * the system instruction, then validates the model's JSON output and returns it as `tool_calls`. Clients
 * always see the OpenAI shape, whichever model is loaded.
 */
internal sealed interface ToolChoice {
    data object None : ToolChoice
    data object Auto : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Reads OpenAI `tool_choice`: `"none"`, `"auto"`, `"required"`, or `{"type":"function","function":{"name":...}}`. */
internal fun parseToolChoice(element: JsonElement?): ToolChoice {
    return when (element) {
        is JsonPrimitive -> when (element.contentOrNull) {
            "none" -> ToolChoice.None
            "required" -> ToolChoice.Required
            else -> ToolChoice.Auto
        }
        is JsonObject -> {
            val name = (element["function"] as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (name != null) ToolChoice.Named(name) else ToolChoice.Auto
        }
        null -> ToolChoice.Auto
        else -> ToolChoice.Auto
    }
}

/** Tools the model may call under [choice]. Empty for [ToolChoice.None]. */
private fun activeTools(tools: List<ToolDefinition>, choice: ToolChoice): List<ToolDefinition> = when (choice) {
    ToolChoice.None -> emptyList()
    is ToolChoice.Named -> tools.filter { it.function.name == choice.name }
    else -> tools
}

/**
 * Builds the system-instruction block that describes [tools] and fixes the JSON output format.
 * Returns null when no tool may be called, so the caller leaves the prompt unchanged.
 */
internal fun buildToolInstruction(tools: List<ToolDefinition>, choice: ToolChoice): String? {
    val active = activeTools(tools, choice)
    if (active.isEmpty()) return null
    val definitions = Json.encodeToString(ListSerializer(ToolDefinition.serializer()), active)
    val obligation = when (choice) {
        ToolChoice.Required -> "Debes responder usando una de las herramientas. Responde SOLO con el JSON de tool_calls."
        is ToolChoice.Named -> "Debes usar la herramienta ${choice.name}. Responde SOLO con el JSON de tool_calls."
        else -> "Si no necesitas ninguna herramienta, responde normalmente en texto."
    }
    return buildString {
        appendLine("Puedes usar las herramientas siguientes. Definiciones en formato OpenAI:")
        appendLine(definitions)
        appendLine("Para usar una herramienta responde SOLO con este JSON y nada más:")
        appendLine("""{"tool_calls":[{"name":"<nombre>","arguments":{...}}]}""")
        append(obligation)
    }
}

/**
 * Parses the model output into OpenAI tool calls. Returns null when the output is not a valid call
 * (no JSON, unknown tool, non-object arguments, missing required argument, truncated JSON). The caller
 * then returns the text as a normal reply, so invalid JSON never reaches the client as a call.
 */
internal fun parseToolCalls(raw: String, tools: List<ToolDefinition>, choice: ToolChoice): List<ToolCall>? {
    val active = activeTools(tools, choice)
    if (active.isEmpty()) return null
    val json = extractJsonObject(raw) ?: return null
    val root = runCatching { lenientJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    val entries: List<JsonObject> = when {
        root["tool_calls"] is JsonArray ->
            root["tool_calls"]!!.jsonArray.mapNotNull { it as? JsonObject }
        root["name"] != null -> listOf(root)
        else -> return null
    }
    if (entries.isEmpty()) return null

    val calls = entries.map { entry ->
        val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val definition = active.firstOrNull { it.function.name == name } ?: return null
        val arguments = when (val raw = entry["arguments"]) {
            is JsonObject -> raw
            is JsonPrimitive -> runCatching { lenientJson.parseToJsonElement(raw.content) as? JsonObject }.getOrNull() ?: return null
            else -> return null
        }
        if (!hasRequiredArguments(definition, arguments)) return null
        ToolCall(
            id = "call_${UUID.randomUUID().toString().take(12)}",
            type = "function",
            function = ToolCallFunction(name = name, arguments = arguments.toString()),
        )
    }
    return calls
}

/** Returns the first `{...}` block of [raw], removing ```json fences and surrounding prose. */
private fun extractJsonObject(raw: String): String? {
    val unfenced = raw.replace(Regex("```(?:json)?"), "")
    val start = unfenced.indexOf('{')
    val end = unfenced.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return unfenced.substring(start, end + 1)
}

private fun hasRequiredArguments(definition: ToolDefinition, arguments: JsonObject): Boolean {
    val required = (definition.function.parameters?.get("required") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    return required.all { it in arguments }
}

/**
 * Renders one chat turn as text for the prompt. Assistant tool calls are shown as the same JSON the
 * model is asked to emit, and tool results are labelled, so history is not flattened into plain prose.
 */
internal fun renderTurn(message: Message): String {
    val calls = message.tool_calls
    if (message.role == "tool") {
        return "Resultado de la herramienta ${message.name ?: "desconocida"} (${message.tool_call_id ?: "-"}): ${message.content.orEmpty()}"
    }
    if (!calls.isNullOrEmpty()) {
        return buildJsonObject {
            putJsonArray("tool_calls") {
                calls.forEach { call ->
                    add(
                        buildJsonObject {
                            put("name", call.function.name)
                            put("arguments", runCatching { lenientJson.parseToJsonElement(call.function.arguments) }.getOrElse { JsonPrimitive(call.function.arguments) })
                        }
                    )
                }
            }
        }.toString()
    }
    return message.content.orEmpty()
}
