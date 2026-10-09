package com.google.ai.edge.gallery.server.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseUtilsTest {
  private var nextId = 0
  private val ids = { "call_${nextId++}" }

  @Test
  fun parsesQwenStyleToolCall() {
    val text =
      "Let me check.\n<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Lima\"}}\n</tool_call>"
    val (content, calls) = ToolCallTextParser.parse(text, ids)
    assertEquals("Let me check.", content)
    assertEquals("get_weather", calls.single().name)
    assertEquals("{\"city\":\"Lima\"}", calls.single().argumentsJson)
    assertEquals("call_0", calls.single().id)
  }

  @Test
  fun parsesMultipleToolCalls() {
    val text =
      "<tool_call>{\"name\":\"a\",\"arguments\":{}}</tool_call><tool_call>{\"name\":\"b\",\"arguments\":{\"x\":1}}</tool_call>"
    val (content, calls) = ToolCallTextParser.parse(text, ids)
    assertEquals("", content)
    assertEquals(listOf("a", "b"), calls.map { it.name })
  }

  @Test
  fun malformedToolCallIsLeftAsText() {
    val text = "<tool_call>not json</tool_call>"
    val (content, calls) = ToolCallTextParser.parse(text, ids)
    assertEquals(text, content)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun stringArgumentsArePassedThrough() {
    val text = "<tool_call>{\"name\":\"a\",\"arguments\":\"{\\\"k\\\":2}\"}</tool_call>"
    val (_, calls) = ToolCallTextParser.parse(text, ids)
    assertEquals("{\"k\":2}", calls.single().argumentsJson)
  }

  @Test
  fun splitsInlineThinking() {
    val (content, reasoning) = ResponseUtils.splitThinking("<think>\nhmm\n</think>\n\nAnswer")
    assertEquals("Answer", content)
    assertEquals("hmm", reasoning)
  }

  @Test
  fun noThinkingReturnsNullReasoning() {
    val (content, reasoning) = ResponseUtils.splitThinking("Answer")
    assertEquals("Answer", content)
    assertNull(reasoning)
  }

  @Test
  fun stopFilterTruncatesAtStopSequence() {
    val f = StopSequenceFilter(listOf("END"))
    val out = StringBuilder()
    out.append(f.feed("hello E"))
    out.append(f.feed("NDworld"))
    out.append(f.flush())
    assertEquals("hello ", out.toString())
    assertTrue(f.stopped)
  }

  @Test
  fun stopFilterWithoutMatchEmitsEverything() {
    val f = StopSequenceFilter(listOf("XYZ"))
    val out = StringBuilder()
    out.append(f.feed("ab"))
    out.append(f.feed("cX"))
    out.append(f.flush())
    assertEquals("abcX", out.toString())
    assertFalse(f.stopped)
  }

  @Test
  fun stopFilterWithNoStopsIsPassthrough() {
    val f = StopSequenceFilter(emptyList())
    assertEquals("abc", f.feed("abc"))
  }

  @Test
  fun completionJsonHasOpenAiShape() {
    val resp =
      ResponseUtils.completion(
        id = "chatcmpl-1",
        created = 100,
        model = "m",
        content = "hi",
        reasoning = "r",
        toolCalls = emptyList(),
        finishReason = "stop",
        usage = Usage(1, 2, 3),
      )
    val obj = Json.parseToJsonElement(OpenAiJson.encodeToString(ChatCompletionResponse.serializer(), resp)).jsonObject
    assertEquals("chat.completion", obj["object"]!!.jsonPrimitive.content)
    val choice = obj["choices"]!!.jsonArray[0].jsonObject
    assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
    val msg = choice["message"]!!.jsonObject
    assertEquals("assistant", msg["role"]!!.jsonPrimitive.content)
    assertEquals("hi", msg["content"]!!.jsonPrimitive.content)
    assertEquals("r", msg["reasoning_content"]!!.jsonPrimitive.content)
    assertFalse(msg.containsKey("tool_calls"))
    assertEquals(3, obj["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun toolCallCompletionHasNullContentAndToolCallsFinishReason() {
    val resp =
      ResponseUtils.completion(
        id = "c",
        created = 1,
        model = "m",
        content = "",
        reasoning = null,
        toolCalls = listOf(ParsedToolCall("call_9", "f", "{}")),
        finishReason = "tool_calls",
        usage = null,
      )
    val obj = Json.parseToJsonElement(OpenAiJson.encodeToString(ChatCompletionResponse.serializer(), resp)).jsonObject
    val msg = obj["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
    assertEquals("null", msg["content"].toString())
    val call = msg["tool_calls"]!!.jsonArray[0].jsonObject
    assertEquals("call_9", call["id"]!!.jsonPrimitive.content)
    assertEquals("function", call["type"]!!.jsonPrimitive.content)
    assertEquals("f", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
  }

  @Test
  fun sseChunkFormat() {
    val chunk = ResponseUtils.chunk("c", 1, "m", ChunkDelta(content = "x"), finishReason = null)
    val line = ResponseUtils.sse(chunk)
    assertTrue(line.startsWith("data: {"))
    assertTrue(line.endsWith("\n\n"))
    assertTrue(line.contains("\"object\":\"chat.completion.chunk\""))
  }

  @Test
  fun errorBodyShape() {
    val e = OpenAiException(404, "nope", param = "model", code = "model_not_found")
    val obj = Json.parseToJsonElement(OpenAiJson.encodeToString(ErrorResponse.serializer(), e.toResponse())).jsonObject
    val err = obj["error"]!!.jsonObject
    assertEquals("nope", err["message"]!!.jsonPrimitive.content)
    assertEquals("model", err["param"]!!.jsonPrimitive.content)
    assertEquals("model_not_found", err["code"]!!.jsonPrimitive.content)
    assertEquals("invalid_request_error", err["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun apiKeyCheck() {
    assertTrue(ApiKeyAuth.isAuthorized(header = null, configuredKey = ""))
    assertTrue(ApiKeyAuth.isAuthorized(header = "Bearer s3cret", configuredKey = "s3cret"))
    assertFalse(ApiKeyAuth.isAuthorized(header = "Bearer nope", configuredKey = "s3cret"))
    assertFalse(ApiKeyAuth.isAuthorized(header = null, configuredKey = "s3cret"))
    assertFalse(ApiKeyAuth.isAuthorized(header = "s3cret", configuredKey = "s3cret"))
  }
}
