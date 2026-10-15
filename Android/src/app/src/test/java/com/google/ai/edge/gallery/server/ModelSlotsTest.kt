package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : SlotStore {
  override fun get(key: String) = map[key]

  override fun put(key: String, value: String) {
    map[key] = value
  }
}

class ModelSlotsTest {
  @Test
  fun slotsPersistAndReload() {
    val store = MapStore()
    ModelSlots(store).set(ModelRole.CHAT, "Qwen")
    assertEquals("Qwen", ModelSlots(store).get(ModelRole.CHAT))
  }

  @Test
  fun legacyPreferencesMigrate() {
    val store = MapStore(mutableMapOf("preferred_chat_model" to "A", "preferred_audio_model" to "B"))
    val slots = ModelSlots(store)
    assertEquals("A", slots.get(ModelRole.CHAT))
    assertEquals("B", slots.get(ModelRole.STT))
    assertEquals("A", store.map["slot_chat"])
  }

  @Test
  fun completionsAndVisionFallBackToChat() {
    val slots = ModelSlots(MapStore())
    slots.set(ModelRole.CHAT, "A")
    assertEquals("A", slots.effective(ModelRole.COMPLETIONS))
    assertEquals("A", slots.effective(ModelRole.VISION))
    slots.set(ModelRole.VISION, "V")
    assertEquals("V", slots.effective(ModelRole.VISION))
  }

  @Test
  fun sttAndRouterDoNotFallBack() {
    val slots = ModelSlots(MapStore())
    slots.set(ModelRole.CHAT, "A")
    assertEquals("", slots.effective(ModelRole.STT))
    assertEquals("", slots.effective(ModelRole.ROUTER))
  }

  @Test
  fun aliasesParseIgnoringCommentsAndBlanks() {
    val map = ModelSlots.parseAliases("# note\nGPT-4o = Qwen3.5-4B\n\nbad line\n=x\nfast=Gemma")
    assertEquals(mapOf("gpt-4o" to "Qwen3.5-4B", "fast" to "Gemma"), map)
  }
}

class ModelMatchingTest {
  private val models = listOf("Qwen3.5-4B" to "q4.litertlm", "Qwen3.5-4B-Vision" to "qv.litertlm", "Gemma-3n" to "g.litertlm")

  private fun find(query: String, aliases: Map<String, String> = emptyMap()) =
    ModelMatching.find(models, query, aliases, { it.first }, { it.second })?.first

  @Test
  fun exactNameWins() = assertEquals("Qwen3.5-4B", find("qwen3.5-4b"))

  @Test
  fun fileNameMatches() = assertEquals("Gemma-3n", find("g.litertlm"))

  @Test
  fun punctuationIsIgnored() = assertEquals("Gemma-3n", find("gemma 3n"))

  @Test
  fun neverMatchesBySubstring() {
    assertNull(find("gpt-4o-Qwen3.5-4B-turbo"))
    assertNull(find("Qwen"))
  }

  @Test
  fun aliasResolves() = assertEquals("Gemma-3n", find("gpt-4o", mapOf("gpt-4o" to "Gemma-3n")))

  @Test
  fun blankQueryMatchesNothing() = assertNull(find("  "))
}

class ModelRouterPromptTest {
  private val names = listOf("Qwen3.5-4B", "Gemma-3n")

  @Test
  fun parsesExactAnswer() = assertEquals("Gemma-3n", ModelRouterPrompt.parse("gemma-3n", names))

  @Test
  fun parsesAnswerInsideSentenceAndAfterThinking() =
    assertEquals("Qwen3.5-4B", ModelRouterPrompt.parse("<think>hmm Gemma-3n?</think>Use Qwen3.5-4B.", names))

  @Test
  fun unknownAnswerIsNull() = assertEquals(null, ModelRouterPrompt.parse("no idea", names))

  @Test
  fun promptListsCandidatesAndTruncatesRequest() {
    val p = ModelRouterPrompt.build(names, "x".repeat(5000))
    assertEquals(true, p.contains("- Gemma-3n"))
    assertEquals(true, p.length < 2000)
  }
}

class RequestLogTest {
  @Test
  fun logsKeepNewestFirstAndCapAt100() {
    PocketNodeState.clearLog()
    for (i in 1..105) {
      PocketNodeState.logRequest(
        RequestLogEntry(
          id = "req_$i",
          timeMillis = i.toLong(),
          method = "POST",
          path = "/v1/chat/completions",
          status = if (i % 2 == 0) 200 else 400,
          durationMs = 10L,
          model = "Qwen3.5-4B",
          error = if (i % 2 == 0) null else "Invalid body",
        )
      )
    }
    assertEquals(100, PocketNodeState.requestLog.size)
    assertEquals("req_105", PocketNodeState.requestLog.first().id)
    assertEquals("Invalid body", PocketNodeState.requestLog.first().error)
    PocketNodeState.clearLog()
    assertEquals(0, PocketNodeState.requestLog.size)
  }
}
