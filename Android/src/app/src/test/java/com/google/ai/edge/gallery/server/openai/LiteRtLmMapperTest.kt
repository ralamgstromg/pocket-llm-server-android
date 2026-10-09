package com.google.ai.edge.gallery.server.openai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmMapperTest {
  private val passthroughAudio: (EncodedAudio) -> ByteArray = { it.bytes }

  @Test
  fun toolDescriptionIsOpenApiFunction() {
    val params = Json.parseToJsonElement("""{"type":"object","properties":{"city":{"type":"string"}}}""") as JsonObject
    val desc = LiteRtLmMapper.toolDescriptionJson(FunctionDefinition("get_weather", "Weather", params))
    val obj = Json.parseToJsonElement(desc).jsonObject
    assertEquals("get_weather", obj["name"]!!.jsonPrimitive.content)
    assertEquals("Weather", obj["description"]!!.jsonPrimitive.content)
    assertEquals(params, obj["parameters"])
  }

  @Test
  fun toolDescriptionWithoutParametersUsesEmptyObjectSchema() {
    val obj = Json.parseToJsonElement(LiteRtLmMapper.toolDescriptionJson(FunctionDefinition("f"))).jsonObject
    assertEquals("object", obj["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun userTurnWithImageAndAudioBecomesUserMessage() {
    val turn =
      ParsedTurn(TurnRole.USER, "hi", images = listOf(byteArrayOf(1)), audios = listOf(EncodedAudio(byteArrayOf(2), "wav")))
    val msg = LiteRtLmMapper.toMessage(listOf(turn), passthroughAudio)
    assertEquals(Role.USER, msg.role)
    val parts = msg.contents.contents
    assertArrayEquals(byteArrayOf(1), (parts[0] as Content.ImageBytes).bytes)
    assertArrayEquals(byteArrayOf(2), (parts[1] as Content.AudioBytes).bytes)
    assertEquals("hi", (parts[2] as Content.Text).text)
  }

  @Test
  fun assistantTurnWithToolCallsKeepsCalls() {
    val turn =
      ParsedTurn(TurnRole.ASSISTANT, "", toolCalls = listOf(ParsedToolCall("call_1", "get_weather", """{"city":"Lima","days":2}""")))
    val msg = LiteRtLmMapper.toMessages(listOf(turn), passthroughAudio).single()
    assertEquals(Role.MODEL, msg.role)
    val call = msg.toolCalls.single()
    assertEquals("get_weather", call.name)
    assertEquals("Lima", call.arguments["city"])
  }

  @Test
  fun toolTurnsBecomeOneToolMessageWithResponses() {
    val turns =
      listOf(
        ParsedTurn(TurnRole.TOOL, """{"temp":20}""", toolCallId = "a", toolName = "f"),
        ParsedTurn(TurnRole.TOOL, "plain text", toolCallId = "b", toolName = "g"),
      )
    val msg = LiteRtLmMapper.toMessage(turns, passthroughAudio)
    assertEquals(Role.TOOL, msg.role)
    val responses = msg.contents.contents.map { it as Content.ToolResponse }
    assertEquals(listOf("f", "g"), responses.map { it.name })
    assertTrue(responses[0].response is Map<*, *>)
    assertEquals("plain text", responses[1].response)
  }

  @Test
  fun outputExtractsTextReasoningAndToolCalls() {
    val msg =
      Message.model(
        Contents.of(Content.Text("Hello "), Content.Text("world")),
        listOf(ToolCall("f", mapOf("x" to 1.0))),
        mapOf("thought" to "because"),
      )
    val out = LiteRtLmMapper.output(msg)
    assertEquals("Hello world", out.text)
    assertEquals("because", out.reasoning)
    assertEquals("f", out.toolCalls.single().first)
    assertEquals("""{"x":1}""", out.toolCalls.single().second)
  }

  @Test
  fun responseFormatMapping() {
    assertNull(LiteRtLmMapper.responseFormat(ResponseFormatSpec.Text))
    val rf = LiteRtLmMapper.responseFormat(ResponseFormatSpec.JsonSchema("""{"type":"object"}"""))!!
    assertEquals("""{"type":"object"}""", rf.schemaOrPattern)
  }
}
