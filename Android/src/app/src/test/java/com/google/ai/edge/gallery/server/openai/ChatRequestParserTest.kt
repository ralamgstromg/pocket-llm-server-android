package com.google.ai.edge.gallery.server.openai

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatRequestParserTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun parse(body: String): ParsedChatRequest =
    ChatRequestParser.parse(json.decodeFromString(ChatCompletionRequest.serializer(), body))

  private fun assertInvalid(body: String, param: String?) {
    try {
      parse(body)
      fail("Expected OpenAiException")
    } catch (e: OpenAiException) {
      assertEquals(400, e.status)
      assertEquals(param, e.param)
    }
  }

  @Test
  fun legacyStringContentStillWorks() {
    val req = parse("""{"model":"Gemma","messages":[{"role":"user","content":"hola"}]}""")
    assertEquals("Gemma", req.model)
    assertEquals(1, req.current.size)
    assertEquals(TurnRole.USER, req.current[0].role)
    assertEquals("hola", req.current[0].text)
    assertTrue(req.history.isEmpty())
    assertNull(req.systemPrompt)
  }

  @Test
  fun systemAndDeveloperMessagesBecomeSystemPrompt() {
    val req =
      parse(
        """{"messages":[{"role":"system","content":"A"},{"role":"developer","content":"B"},
        {"role":"user","content":"q"}]}"""
      )
    assertEquals("A\n\nB", req.systemPrompt)
    assertTrue(req.history.isEmpty())
  }

  @Test
  fun previousTurnsGoToHistory() {
    val req =
      parse(
        """{"messages":[{"role":"user","content":"1"},{"role":"assistant","content":"2"},
        {"role":"user","content":"3"}]}"""
      )
    assertEquals(listOf(TurnRole.USER, TurnRole.ASSISTANT), req.history.map { it.role })
    assertEquals("3", req.current.single().text)
  }

  @Test
  fun contentPartsWithTextAndDataUriImage() {
    val png = byteArrayOf(1, 2, 3)
    val b64 = Base64.getEncoder().encodeToString(png)
    val req =
      parse(
        """{"messages":[{"role":"user","content":[{"type":"text","text":"what"},
        {"type":"image_url","image_url":{"url":"data:image/png;base64,$b64"}},
        {"type":"text","text":"is it"}]}]}"""
      )
    val turn = req.current.single()
    assertEquals("what\nis it", turn.text)
    assertArrayEquals(png, turn.images.single())
  }

  @Test
  fun remoteImageUrlIsRejected() {
    assertInvalid(
      """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"http://x/y.png"}}]}]}""",
      "messages[0].content",
    )
  }

  @Test
  fun inputAudioPartIsDecoded() {
    val wav = byteArrayOf(9, 8)
    val b64 = Base64.getEncoder().encodeToString(wav)
    val req =
      parse(
        """{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"$b64","format":"wav"}}]}]}"""
      )
    val audio = req.current.single().audios.single()
    assertArrayEquals(wav, audio.bytes)
    assertEquals("wav", audio.format)
  }

  @Test
  fun emptyMessagesIsInvalid() {
    assertInvalid("""{"messages":[]}""", "messages")
  }

  @Test
  fun nOutOfRangeIsInvalid() {
    assertInvalid("""{"n":0,"messages":[{"role":"user","content":"x"}]}""", "n")
    assertInvalid("""{"n":9,"messages":[{"role":"user","content":"x"}]}""", "n")
  }

  @Test
  fun nGreaterThanOneWithStreamIsInvalid() {
    assertInvalid("""{"n":2,"stream":true,"messages":[{"role":"user","content":"x"}]}""", "n")
  }

  @Test
  fun temperatureOutOfRangeIsInvalid() {
    assertInvalid("""{"temperature":3,"messages":[{"role":"user","content":"x"}]}""", "temperature")
  }

  @Test
  fun lastMessageFromAssistantIsInvalid() {
    assertInvalid(
      """{"messages":[{"role":"user","content":"x"},{"role":"assistant","content":"y"}]}""",
      "messages",
    )
  }

  @Test
  fun samplingParametersAreMapped() {
    val req =
      parse(
        """{"temperature":0.2,"top_p":0.9,"top_k":40,"max_tokens":10,"max_completion_tokens":20,
        "seed":7,"stop":"END","stream":true,"stream_options":{"include_usage":true},
        "messages":[{"role":"user","content":"x"}]}"""
      )
    assertEquals(0.2, req.temperature!!, 1e-9)
    assertEquals(0.9, req.topP!!, 1e-9)
    assertEquals(40, req.topK)
    assertEquals(20, req.maxTokens)
    assertEquals(7, req.seed)
    assertEquals(listOf("END"), req.stop)
    assertTrue(req.stream)
    assertTrue(req.includeUsage)
  }

  @Test
  fun stopAsArray() {
    val req = parse("""{"stop":["a","b"],"messages":[{"role":"user","content":"x"}]}""")
    assertEquals(listOf("a", "b"), req.stop)
  }

  @Test
  fun toolsAreParsedAndAutoByDefault() {
    val req =
      parse(
        """{"tools":[{"type":"function","function":{"name":"get_weather","description":"d",
        "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],
        "messages":[{"role":"user","content":"x"}]}"""
      )
    assertEquals("get_weather", req.tools.single().name)
    assertEquals(ToolChoice.Auto, req.toolChoice)
  }

  @Test
  fun toolChoiceNoneRemovesTools() {
    val req =
      parse(
        """{"tool_choice":"none","tools":[{"type":"function","function":{"name":"f"}}],
        "messages":[{"role":"user","content":"x"}]}"""
      )
    assertTrue(req.tools.isEmpty())
    assertEquals(ToolChoice.None, req.toolChoice)
  }

  @Test
  fun namedToolChoiceKeepsOnlyThatTool() {
    val req =
      parse(
        """{"tool_choice":{"type":"function","function":{"name":"g"}},
        "tools":[{"type":"function","function":{"name":"f"}},{"type":"function","function":{"name":"g"}}],
        "messages":[{"role":"user","content":"x"}]}"""
      )
    assertEquals(listOf("g"), req.tools.map { it.name })
    assertEquals(ToolChoice.Named("g"), req.toolChoice)
  }

  @Test
  fun namedToolChoiceForUnknownToolIsInvalid() {
    assertInvalid(
      """{"tool_choice":{"type":"function","function":{"name":"zz"}},
      "tools":[{"type":"function","function":{"name":"f"}}],"messages":[{"role":"user","content":"x"}]}""",
      "tool_choice",
    )
  }

  @Test
  fun invalidToolNameIsInvalid() {
    assertInvalid(
      """{"tools":[{"type":"function","function":{"name":"bad name!"}}],"messages":[{"role":"user","content":"x"}]}""",
      "tools[0].function.name",
    )
  }

  @Test
  fun toolResultRoundTrip() {
    val req =
      parse(
        """{"tools":[{"type":"function","function":{"name":"get_weather"}}],"messages":[
        {"role":"user","content":"weather?"},
        {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function",
          "function":{"name":"get_weather","arguments":"{\"city\":\"Lima\"}"}}]},
        {"role":"tool","tool_call_id":"call_1","content":"{\"temp\":20}"}]}"""
      )
    val assistant = req.history[1]
    assertEquals(TurnRole.ASSISTANT, assistant.role)
    assertEquals("get_weather", assistant.toolCalls.single().name)
    assertEquals("{\"city\":\"Lima\"}", assistant.toolCalls.single().argumentsJson)
    val tool = req.current.single()
    assertEquals(TurnRole.TOOL, tool.role)
    assertEquals("get_weather", tool.toolName)
    assertEquals("call_1", tool.toolCallId)
    assertEquals("{\"temp\":20}", tool.text)
  }

  @Test
  fun parallelToolResultsAreAllCurrent() {
    val req =
      parse(
        """{"messages":[{"role":"user","content":"x"},
        {"role":"assistant","tool_calls":[
          {"id":"a","type":"function","function":{"name":"f","arguments":"{}"}},
          {"id":"b","type":"function","function":{"name":"g","arguments":"{}"}}]},
        {"role":"tool","tool_call_id":"a","content":"1"},
        {"role":"tool","tool_call_id":"b","content":"2"}]}"""
      )
    assertEquals(listOf("f", "g"), req.current.map { it.toolName })
  }

  @Test
  fun toolMessageWithUnknownCallIdIsInvalid() {
    assertInvalid(
      """{"messages":[{"role":"user","content":"x"},{"role":"tool","tool_call_id":"nope","content":"1"}]}""",
      "messages[1].tool_call_id",
    )
  }

  @Test
  fun responseFormatJsonSchema() {
    val req =
      parse(
        """{"response_format":{"type":"json_schema","json_schema":{"name":"r",
        "schema":{"type":"object"}}},"messages":[{"role":"user","content":"x"}]}"""
      )
    val rf = req.responseFormat as ResponseFormatSpec.JsonSchema
    assertEquals("""{"type":"object"}""", rf.schemaJson)
  }

  @Test
  fun responseFormatJsonObject() {
    val req =
      parse("""{"response_format":{"type":"json_object"},"messages":[{"role":"user","content":"x"}]}""")
    assertEquals(ResponseFormatSpec.JsonObject, req.responseFormat)
  }

  @Test
  fun reasoningEffortControlsThinking() {
    assertNull(parse("""{"messages":[{"role":"user","content":"x"}]}""").enableThinking)
    assertFalse(
      parse("""{"reasoning_effort":"none","messages":[{"role":"user","content":"x"}]}""").enableThinking!!
    )
    assertTrue(
      parse("""{"reasoning_effort":"high","messages":[{"role":"user","content":"x"}]}""").enableThinking!!
    )
  }

  @Test
  fun topPZeroIsAccepted() {
    val req = parse("""{"top_p":0,"messages":[{"role":"user","content":"x"}]}""")
    assertEquals(0.0, req.topP!!, 1e-12)
  }
}
