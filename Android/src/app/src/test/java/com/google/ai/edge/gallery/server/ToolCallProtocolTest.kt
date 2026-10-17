package com.google.ai.edge.gallery.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallProtocolTest {

    private val weatherSchema = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
    private val weather = ToolDefinition(
        function = FunctionDefinition(
            name = "get_weather",
            description = "Tiempo actual",
            parameters = Json.parseToJsonElement(weatherSchema) as JsonObject,
        ),
    )
    private val tools = listOf(weather)

    @Test
    fun parseToolChoice_readsStringAndNamedForms() {
        assertEquals(ToolChoice.Auto, parseToolChoice(null))
        assertEquals(ToolChoice.None, parseToolChoice(Json.parseToJsonElement("\"none\"")))
        assertEquals(ToolChoice.Required, parseToolChoice(Json.parseToJsonElement("\"required\"")))
        assertEquals(
            ToolChoice.Named("get_weather"),
            parseToolChoice(Json.parseToJsonElement("""{"type":"function","function":{"name":"get_weather"}}""")),
        )
    }

    @Test
    fun buildToolInstruction_nullWhenNoToolMayBeCalled() {
        assertNull(buildToolInstruction(tools, ToolChoice.None))
        assertNull(buildToolInstruction(emptyList(), ToolChoice.Auto))
        assertNull(buildToolInstruction(tools, ToolChoice.Named("otra")))
    }

    @Test
    fun buildToolInstruction_describesToolsAndOutputFormat() {
        val auto = buildToolInstruction(tools, ToolChoice.Auto)!!
        assertTrue(auto.contains("get_weather"))
        assertTrue(auto.contains("\"tool_calls\""))
        assertTrue(auto.contains("normalmente en texto"))
        assertTrue(buildToolInstruction(tools, ToolChoice.Required)!!.contains("Debes responder"))
    }

    @Test
    fun parseToolCalls_validToolCallsArray() {
        val calls = parseToolCalls(
            """{"tool_calls":[{"name":"get_weather","arguments":{"city":"Madrid"}}]}""",
            tools,
            ToolChoice.Auto,
        )!!
        assertEquals(1, calls.size)
        assertEquals("get_weather", calls[0].function.name)
        assertEquals("function", calls[0].type)
        assertTrue(calls[0].id.startsWith("call_"))
        assertEquals("Madrid", Json.parseToJsonElement(calls[0].function.arguments).jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseToolCalls_acceptsFencedAndSurroundedJson() {
        val fenced = "```json\n{\"tool_calls\":[{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Madrid\"}}]}\n```"
        assertEquals(1, parseToolCalls(fenced, tools, ToolChoice.Auto)!!.size)
        val surrounded = "Voy a consultar: {\"name\":\"get_weather\",\"arguments\":{\"city\":\"Sevilla\"}} listo"
        assertEquals(1, parseToolCalls(surrounded, tools, ToolChoice.Auto)!!.size)
    }

    @Test
    fun parseToolCalls_acceptsArgumentsAsJsonString() {
        val calls = parseToolCalls(
            """{"tool_calls":[{"name":"get_weather","arguments":"{\"city\":\"Madrid\"}"}]}""",
            tools,
            ToolChoice.Auto,
        )
        assertEquals(1, calls!!.size)
    }

    @Test
    fun parseToolCalls_rejectsInvalidOutput() {
        assertNull(parseToolCalls("Hola, ¿en qué te ayudo?", tools, ToolChoice.Auto))
        assertNull(parseToolCalls("""{"tool_calls":[{"name":"borrar_todo","arguments":{}}]}""", tools, ToolChoice.Auto))
        assertNull(parseToolCalls("""{"tool_calls":[{"name":"get_weather","arguments":{}}]}""", tools, ToolChoice.Auto))
        assertNull(parseToolCalls("""{"tool_calls":[{"name":"get_weather","arguments":5}]}""", tools, ToolChoice.Auto))
        assertNull(parseToolCalls("""{"tool_calls":[{"name":"get_weather","arguments":{"city":"Mad""", tools, ToolChoice.Auto))
    }

    @Test
    fun parseToolCalls_noneChoiceNeverCalls() {
        assertNull(parseToolCalls("""{"name":"get_weather","arguments":{"city":"Madrid"}}""", tools, ToolChoice.None))
    }

    @Test
    fun renderTurn_toolResultIsLabelled() {
        val text = renderTurn(Message(role = "tool", name = "get_weather", tool_call_id = "call_1", content = "soleado"))
        assertEquals("Resultado de la herramienta get_weather (call_1): soleado", text)
    }

    @Test
    fun renderTurn_assistantToolCallsAreJson() {
        val message = Message(
            role = "assistant",
            tool_calls = listOf(ToolCall("call_1", "function", ToolCallFunction("get_weather", """{"city":"Madrid"}"""))),
        )
        val text = renderTurn(message)
        assertEquals("""{"tool_calls":[{"name":"get_weather","arguments":{"city":"Madrid"}}]}""", text)
    }

    @Test
    fun buildChatPromptContext_keepsToolHistoryStructured() {
        val context = PocketNodeInference.buildChatPromptContext(
            listOf(
                Message(role = "user", content = "¿Tiempo en Madrid?"),
                Message(
                    role = "assistant",
                    tool_calls = listOf(ToolCall("call_1", "function", ToolCallFunction("get_weather", """{"city":"Madrid"}"""))),
                ),
                Message(role = "tool", name = "get_weather", tool_call_id = "call_1", content = "soleado"),
            )
        )
        assertTrue(context.userPrompt.contains("[Asistente]: {\"tool_calls\""))
        assertTrue(context.userPrompt.contains("Resultado de la herramienta get_weather (call_1): soleado"))
    }

    @Test
    fun chatResponse_serializesNullContentAndStringArguments() {
        val reply = ChatResponse(
            id = "chatcmpl-1",
            model = "m",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(
                        role = "assistant",
                        content = null,
                        tool_calls = listOf(ToolCall("call_1", "function", ToolCallFunction("get_weather", """{"city":"Madrid"}"""))),
                    ),
                    finish_reason = "tool_calls",
                ),
            ),
        )
        val json = Json.encodeToString(ChatResponse.serializer(), reply)
        assertTrue(json.contains("\"content\":null"))
        assertTrue(json.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(json.contains("\"arguments\":\"{\\\"city\\\":\\\"Madrid\\\"}\""))
    }
}
