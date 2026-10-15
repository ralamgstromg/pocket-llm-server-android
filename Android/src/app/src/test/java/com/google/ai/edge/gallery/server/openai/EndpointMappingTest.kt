package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompletionsApiTest {
  private fun req(json: String) = OpenAiJson.decodeFromString(CompletionRequest.serializer(), json)

  @Test
  fun stringPromptBecomesUserMessage() {
    val (chat, prompt) = CompletionsApi.toChatRequest(req("""{"model":"m","prompt":"Once upon","max_tokens":5,"n":2}"""))
    assertEquals("Once upon", prompt)
    assertEquals("user", chat.messages.single().role)
    assertEquals(5, chat.maxTokens)
    assertEquals(2, chat.n)
  }

  @Test
  fun singleItemArrayPromptIsAccepted() {
    assertEquals("hi", CompletionsApi.promptText(req("""{"prompt":["hi"]}""").prompt))
  }

  @Test
  fun unsupportedFieldsAreRejectedWithParam() {
    listOf(
      """{"prompt":"x","suffix":"y"}""" to "suffix",
      """{"prompt":"x","logprobs":2}""" to "logprobs",
      """{"prompt":"x","best_of":3}""" to "best_of",
      """{"prompt":["a","b"]}""" to "prompt",
      """{}""" to "prompt",
    ).forEach { (body, param) ->
      try {
        CompletionsApi.toChatRequest(req(body))
        fail("expected rejection for $body")
      } catch (e: OpenAiException) {
        assertEquals(param, e.param)
      }
    }
  }
}

class ResponsesApiTest {
  private fun convert(json: String) = ResponsesApi.toChatRequest(OpenAiJson.decodeFromString(ResponsesRequest.serializer(), json))

  @Test
  fun stringInputWithInstructions() {
    val chat = convert("""{"model":"m","input":"Hello","instructions":"Be brief","max_output_tokens":9}""")
    assertEquals(listOf("system", "user"), chat.messages.map { it.role })
    assertEquals(9, chat.maxCompletionTokens)
    // The result must be valid for the chat parser.
    ChatRequestParser.parse(chat)
  }

  @Test
  fun flatToolsAndNamedChoiceMapToChatShape() {
    val chat =
      convert(
        """{"input":"x","tools":[{"type":"function","name":"get_weather","description":"d","parameters":{"type":"object"}}],
           "tool_choice":{"type":"function","name":"get_weather"}}"""
      )
    val parsed = ChatRequestParser.parse(chat)
    assertEquals("get_weather", parsed.tools.single().name)
    assertEquals(ToolChoice.Named("get_weather"), parsed.toolChoice)
  }

  @Test
  fun functionCallRoundTripBecomesAssistantAndToolMessages() {
    val chat =
      convert(
        """{"input":[
          {"role":"user","content":[{"type":"input_text","text":"weather?"}]},
          {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
          {"type":"function_call_output","call_id":"c1","output":"sunny"}],
          "tools":[{"type":"function","name":"get_weather"}]}"""
      )
    assertEquals(listOf("user", "assistant", "tool"), chat.messages.map { it.role })
    val parsed = ChatRequestParser.parse(chat)
    assertEquals("sunny", parsed.current.single().text)
  }

  @Test
  fun missingInputIsRejected() {
    try {
      convert("""{"model":"m"}""")
      fail()
    } catch (e: OpenAiException) {
      assertEquals("input", e.param)
    }
  }

  @Test
  fun outputItemsAndResponseShape() {
    val items = ResponsesApi.outputItems("resp_1", "hi", listOf(ParsedToolCall("call_1", "f", "{}")))
    assertEquals(listOf("message", "function_call"), items.map { it["type"]!!.jsonPrimitive.content })
    val r = ResponsesApi.response("resp_1", 1L, "m", "completed", items, Usage(1, 2, 3))
    assertEquals("response", r["object"]!!.jsonPrimitive.content)
    assertEquals(2, r["output"]!!.jsonArray.size)
    assertEquals(3, r["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun emptyAnswerStillProducesAMessage() {
    assertEquals(1, ResponsesApi.outputItems("r", "", emptyList()).size)
  }

  @Test
  fun sseHasEventAndSequence() {
    val s = ResponsesApi.sse("response.created", 3) { }
    assertTrue(s.startsWith("event: response.created\ndata: "))
    assertTrue(s.contains("\"sequence_number\":3"))
  }
}

class TranscriptSanitizerTest {
  @Test
  fun stripsThinkingFencesLabelsAndQuotes() {
    assertEquals("hola mundo", TranscriptSanitizer.sanitize("<think>x</think>Transcription: \"hola mundo\""))
    assertEquals("plain", TranscriptSanitizer.sanitize("```text\nplain\n```"))
  }

  @Test
  fun leavesNormalTextAlone() = assertEquals("just text", TranscriptSanitizer.sanitize("  just text "))
}
