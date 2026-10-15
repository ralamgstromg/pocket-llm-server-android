package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallFormatsTest {
  private var n = 0
  private val ids = { "call_${n++}" }
  private val allowed = setOf("get_weather", "send")

  private fun parse(text: String, allow: Set<String>? = allowed) = ToolCallTextParser.parse(text, ids, allow)

  private fun args(call: ParsedToolCall) = OpenAiJson.parseToJsonElement(call.argumentsJson).jsonObject

  @Test
  fun hermesBlock() {
    val (rest, calls) = parse("Ok.\n<tool_call>{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Lima\"}}</tool_call>")
    assertEquals("Ok.", rest)
    assertEquals("Lima", args(calls.single())["city"]!!.jsonPrimitive.content)
  }

  @Test
  fun mistralArray() {
    val (rest, calls) = parse("[TOOL_CALLS][{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Quito\"}},{\"name\":\"send\",\"arguments\":{}}]")
    assertEquals("", rest)
    assertEquals(listOf("get_weather", "send"), calls.map { it.name })
  }

  @Test
  fun mistralNamedArgs() {
    val (_, calls) = parse("[TOOL_CALLS]get_weather[ARGS]{\"city\": \"Bogota\"}")
    assertEquals("Bogota", args(calls.single())["city"]!!.jsonPrimitive.content)
  }

  @Test
  fun pythonicLiquidFormat() {
    val (rest, calls) = parse("<|tool_call_start|>[get_weather(city=\"Lima\", days=3, metric=True)]<|tool_call_end|>")
    assertEquals("", rest)
    val a = args(calls.single())
    assertEquals("Lima", a["city"]!!.jsonPrimitive.content)
    assertEquals("3", a["days"]!!.jsonPrimitive.content)
    assertEquals("true", a["metric"]!!.jsonPrimitive.content)
  }

  @Test
  fun pythonicMultipleCalls() {
    val (_, calls) = parse("<|tool_call_start|>[get_weather(city='Lima'), send(to=\"a\", tags=[\"x\",\"y\"])]<|tool_call_end|>")
    assertEquals(listOf("get_weather", "send"), calls.map { it.name })
  }

  @Test
  fun graniteUnclosedMarker() {
    val (rest, calls) = parse("<|tool_call|>[{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Cusco\"}}]")
    assertEquals("", rest)
    assertEquals("get_weather", calls.single().name)
  }

  @Test
  fun functionCallTag() {
    val (_, calls) = parse("<function_call>{\"name\":\"send\",\"arguments\":{\"to\":\"x\"}}</function_call>")
    assertEquals("send", calls.single().name)
  }

  @Test
  fun fencedJsonBlock() {
    val (rest, calls) = parse("Aqui:\n```json\n{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Lima\"}}\n```")
    assertEquals("Aqui:", rest)
    assertEquals("get_weather", calls.single().name)
  }

  @Test
  fun bareJsonAnswer() {
    val (rest, calls) = parse("{\"name\": \"get_weather\", \"parameters\": {\"city\": \"Lima\"}}")
    assertEquals("", rest)
    assertEquals("get_weather", calls.single().name)
  }

  @Test
  fun bareJsonForUnknownToolStaysText() {
    val text = "{\"name\": \"rm_rf\", \"arguments\": {}}"
    val (rest, calls) = parse(text)
    assertTrue(calls.isEmpty())
    assertEquals(text, rest)
  }

  @Test
  fun bareJsonIgnoredWithoutAllowedList() {
    val (_, calls) = parse("{\"name\": \"get_weather\", \"arguments\": {}}", allow = null)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun argumentsAreRepairedAndAlwaysAnObject() {
    val (_, calls) = parse("<tool_call>{'name': 'get_weather', 'arguments': {'city': 'Lima',}}</tool_call>")
    assertEquals("Lima", args(calls.single())["city"]!!.jsonPrimitive.content)
    val (_, strCalls) = parse("<tool_call>{\"name\":\"send\",\"arguments\":\"{\\\"to\\\":\\\"a\\\"}\"}</tool_call>")
    assertTrue(OpenAiJson.parseToJsonElement(strCalls.single().argumentsJson) is JsonObject)
  }

  @Test
  fun nonObjectArgumentsDropTheCall() {
    val text = "<tool_call>{\"name\":\"send\",\"arguments\":[1,2]}</tool_call>"
    val (rest, calls) = parse(text)
    assertTrue(calls.isEmpty())
    assertEquals(text, rest)
  }

  @Test
  fun normalizeNativeArguments() {
    assertEquals("{}", ToolCallTextParser.normalizeArguments(""))
    assertEquals("{\"a\":1}", ToolCallTextParser.normalizeArguments("{\"a\":1,}"))
    assertNull(ToolCallTextParser.normalizeArguments("not json"))
    assertNull(ToolCallTextParser.normalizeArguments("[1]"))
  }

  @Test
  fun malformedPythonicIsNotACall() = assertNull(PythonicCallParser.parse("get_weather(city="))

  @Test
  fun streamFilterHoldsEveryMarker() {
    for (marker in ToolCallTextParser.MARKERS) {
      val f = ToolCallStreamFilter()
      val streamed = "Hola ${marker.take(3)}".let { f.feed(it) } + f.feed(marker.drop(3) + "{}")
      assertEquals("Hola ", streamed)
      assertTrue(f.finish().startsWith(marker))
    }
  }

  @Test
  fun toolPromptListsToolsAndFormat() {
    val tools = listOf(FunctionDefinition("get_weather", "Clima", null))
    assertTrue(ToolPrompt.hint(tools, "pythonic").contains("<|tool_call_start|>"))
    assertTrue(ToolPrompt.hint(tools, "mistral").contains("[TOOL_CALLS]"))
    assertTrue(ToolPrompt.hint(tools, "").contains("<tool_call>"))
    assertTrue(!ToolPrompt.needsHint("native") && !ToolPrompt.needsHint("hermes") && ToolPrompt.needsHint(""))
  }
}
