package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketNodeApiTesterTest {

    private val chatOk = """{"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":"ok"}}]}"""

    @Test
    fun checkHealth_passesOnDocumentedBody() {
        assertNull(PocketNodeApiTester.checkHealth(200, "Pocket Node AI is running!\n").error)
    }

    @Test
    fun checkChat_successIsNotAnError() {
        // Regression: a correct answer used to be reported as ❌ with its text as the error.
        val verdict = PocketNodeApiTester.checkChat(200, chatOk)
        assertNull(verdict.error)
        assertEquals("Respuesta: ok", verdict.info)
    }

    @Test
    fun checkChat_inferenceErrorMarkerFails() {
        assertNotNull(PocketNodeApiTester.checkChat(200, """{"choices":[{"message":{"content":"[Error: boom]"}}]}""").error)
    }

    @Test
    fun checkChat_httpErrorFails() {
        assertTrue(PocketNodeApiTester.checkChat(503, "Error: model missing").error!!.startsWith("HTTP 503"))
    }

    @Test
    fun checkChat_missingContentFails() {
        assertNotNull(PocketNodeApiTester.checkChat(200, """{"choices":[]}""").error)
    }

    @Test
    fun checkTranscription_successIsNotAnError_andShowsTimings() {
        val verdict = PocketNodeApiTester.checkTranscription(200, """{"text":"Gracias."}""", "mel=12ms encode=900ms")
        assertNull(verdict.error)
        assertTrue(verdict.info.contains("Gracias."))
        assertTrue(verdict.info.contains("mel=12ms encode=900ms"))
    }

    @Test
    fun checkTranscription_emptyTextIsAcceptedForSilence() {
        assertNull(PocketNodeApiTester.checkTranscription(200, """{"text":""}""", null).error)
    }

    @Test
    fun checkTranscription_missingTextFails() {
        assertNotNull(PocketNodeApiTester.checkTranscription(200, """{"other":1}""", null).error)
    }

    @Test
    fun headerSafe_keepsPrintableAsciiNames() {
        assertEquals("Gemma-4-E2B-it", headerSafe("Gemma-4-E2B-it"))
        assertEquals("Whisper large v3", headerSafe("Whisper large v3"))
    }

    @Test
    fun headerSafe_dropsLineBreaksAndNonAscii() {
        assertEquals("ModeloNombre", headerSafe("Modelo\r\nNombre"))
        assertEquals("Qwen3-0.6B", headerSafe("Qwen3-0.6B\u00e9\u0000"))
    }
}
