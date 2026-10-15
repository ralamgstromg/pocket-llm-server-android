package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceChoiceTest {
  private val voices =
    listOf(
      VoiceInfo("es-us-x-a-network", "es-US", 400, true),
      VoiceInfo("es-us-x-b-local", "es-US", 400, false),
      VoiceInfo("es-es-x-a-local", "es-ES", 300, false),
      VoiceInfo("es-es-x-b-local", "es-ES", 200, false),
      VoiceInfo("en-us-x-a-local", "en-US", 400, false),
    )

  @Test
  fun exactSystemVoiceWins() = assertEquals("en-us-x-a-local", VoiceChoice.pick(voices, "EN-US-X-A-LOCAL", "es-MX")!!.name)

  @Test
  fun openAiNameMapsIntoConfiguredLanguage() {
    val v = VoiceChoice.pick(voices, "alloy", "es-ES")!!
    assertEquals("es-es-x-a-local", v.name)
    assertEquals("es-es-x-b-local", VoiceChoice.pick(voices, "ash", "es-ES")!!.name)
  }

  @Test
  fun sameNameGivesSameVoice() = assertEquals(VoiceChoice.pick(voices, "nova", "es"), VoiceChoice.pick(voices, "nova", "es"))

  @Test
  fun offlineVoicesComeBeforeNetworkOnes() = assertEquals("es-us-x-b-local", VoiceChoice.pick(voices, null, "es-US")!!.name)

  @Test
  fun missingLanguageReturnsNull() = assertNull(VoiceChoice.pick(voices, "alloy", "ja-JP"))
}
