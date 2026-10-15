package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.server.openai.ChatMessageDto
import com.google.ai.edge.gallery.server.openai.ChatRequestParser
import com.google.ai.edge.gallery.server.openai.FunctionCallDto
import com.google.ai.edge.gallery.server.openai.ParsedToolCall
import com.google.ai.edge.gallery.server.openai.ToolCallDto
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSkillExecutor(
  val loadAnswers: Map<String, String> = emptyMap(),
  val jsAnswers: Map<String, String> = emptyMap(),
) : SkillExecutor {
  val loaded = mutableListOf<String>()
  val runCalls = mutableListOf<Triple<String, String, String>>()

  override fun loadSkill(name: String): SkillToolOutcome {
    loaded.add(name)
    val body = loadAnswers[name] ?: return SkillToolOutcome("""{"error":"not found"}""", isError = true)
    return SkillToolOutcome("""{"skill":"$name","doc":"$body"}""")
  }

  override suspend fun runJs(name: String, scriptName: String, data: String): SkillToolOutcome {
    runCalls.add(Triple(name, scriptName, data))
    val body = jsAnswers[name] ?: return SkillToolOutcome("""{"error":"not found"}""", isError = true)
    return SkillToolOutcome(body)
  }
}

class SkillLoopTest {
  @Test
  fun executionHelperRoutesLoadAndRun() {
    val exec = FakeSkillExecutor(mapOf("hash" to "instructions"), mapOf("hash" to """{"hash":"abc"}"""))
    val loadRes = exec.loadSkill("hash")
    assertTrue(loadRes.resultJson.contains("instructions"))
    assertEquals(listOf("hash"), exec.loaded)
  }

  @Test
  fun catalogSnippetsDescribeSkills() {
    val snippet = SkillCatalog.LOAD_SKILL_TOOL
    assertEquals("load_skill", snippet.function.name)
    assertEquals("run_skill_js", SkillCatalog.RUN_JS_TOOL.function.name)
  }

  @Test
  fun skillToolsSetMatchesExpectedNames() {
    assertEquals(setOf("load_skill", "run_skill_js"), SkillLoop.SKILL_TOOLS)
  }
}
