package com.google.ai.edge.gallery.server.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperDecodeBudgetTest {

    @Test
    fun maxNewTokens_emptyWindowGetsMinimumBudget() {
        assertEquals(8, WhisperEngine.maxNewTokens(0))
    }

    @Test
    fun maxNewTokens_scalesWithDuration() {
        assertEquals(12, WhisperEngine.maxNewTokens(8000)) // 0.5 s -> 8 + 4
        assertEquals(16, WhisperEngine.maxNewTokens(16000)) // 1 s -> 8 + 8
    }

    @Test
    fun maxNewTokens_isCappedByDecoderLength() {
        assertEquals(124, WhisperEngine.maxNewTokens(480000)) // 30 s window hits MAX_LEN - PROMPT_LENGTH
    }

    @Test
    fun resolveLanguageCode_defaultsToSpanish() {
        assertEquals("es", WhisperEngine.resolveLanguageCode(null))
        assertEquals("es", WhisperEngine.resolveLanguageCode(""))
        assertEquals("es", WhisperEngine.resolveLanguageCode("spanish"))
        assertEquals("en", WhisperEngine.resolveLanguageCode("en"))
        assertNull(WhisperEngine.resolveLanguageCode("auto"))
    }

    @Test
    fun optimalThreadCount_capsThreadsToSaveBattery() {
        assertEquals(2, WhisperEngine.optimalThreadCount(2))
        assertEquals(2, WhisperEngine.optimalThreadCount(4))
        assertEquals(4, WhisperEngine.optimalThreadCount(8))
        assertEquals(4, WhisperEngine.optimalThreadCount(12))
    }
}
