package com.google.ai.edge.gallery.server.openai

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallStreamFilterTest {
  private fun run(vararg deltas: String): Pair<String, String> {
    val f = ToolCallStreamFilter()
    val streamed = deltas.joinToString("") { f.feed(it) }
    return Pair(streamed, f.finish())
  }

  @Test
  fun plainTextStreamsFully() {
    val (streamed, held) = run("Hello ", "world")
    assertEquals("Hello world", streamed + held)
    assertEquals("", held)
  }

  @Test
  fun textBeforeCallStreamsAndCallIsHeld() {
    val (streamed, held) = run("Sure. ", "<tool_call>{\"name\":\"a\"}", "</tool_call>")
    assertEquals("Sure. ", streamed)
    assertEquals("<tool_call>{\"name\":\"a\"}</tool_call>", held)
  }

  @Test
  fun openTagSplitAcrossDeltasIsHeld() {
    val (streamed, held) = run("ok <tool", "_call>{}</tool_call>")
    assertEquals("ok ", streamed)
    assertEquals("<tool_call>{}</tool_call>", held)
  }

  @Test
  fun lookalikePrefixIsReleasedWhenItDiverges() {
    val (streamed, held) = run("a <to", "day is fine")
    assertEquals("a <today is fine", streamed + held)
  }

  @Test
  fun trailingPrefixIsHeldUntilFinish() {
    val (streamed, held) = run("done <tool")
    assertEquals("done ", streamed)
    assertEquals("<tool", held)
  }
}
