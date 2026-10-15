package com.google.ai.edge.gallery.server.openai

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSanitizerExtraTest {
  @Test
  fun qwenAsrPrefixIsRemovedAndLanguageDetected() {
    val raw = "language Spanish<asr_text>Hola, ¿cómo estás?"
    assertEquals("Hola, ¿cómo estás?", TranscriptSanitizer.sanitize(raw))
    assertEquals("Spanish", TranscriptSanitizer.detectedLanguage(raw))
  }

  @Test
  fun noPrefixMeansNoLanguage() = assertEquals(null, TranscriptSanitizer.detectedLanguage("hola"))
}
