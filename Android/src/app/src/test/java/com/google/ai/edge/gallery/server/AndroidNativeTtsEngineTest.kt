package com.google.ai.edge.gallery.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeTtsEngineTest {

    private val voices = listOf(
        VoiceCandidate("es-es-x-local", "es-ES"),
        VoiceCandidate("en-us-x-local", "en-US"),
    )

    @Test
    fun validateSpeechRequest_acceptsDocumentedInput() {
        assertNull(AndroidNativeTtsEngine.validateSpeechRequest("Hola", 1.0, "wav"))
        assertNull(AndroidNativeTtsEngine.validateSpeechRequest("Hola", null, "pcm"))
    }

    @Test
    fun validateSpeechRequest_rejectsBlankOrTooLongInput() {
        assertTrue(AndroidNativeTtsEngine.validateSpeechRequest("   ", null, "wav")!!.contains("'input'"))
        val tooLong = "a".repeat(AndroidNativeTtsEngine.MAX_INPUT_CHARS + 1)
        assertTrue(AndroidNativeTtsEngine.validateSpeechRequest(tooLong, null, "wav")!!.contains("4096"))
    }

    @Test
    fun validateSpeechRequest_checksSpeedRange() {
        assertNull(AndroidNativeTtsEngine.validateSpeechRequest("Hola", 0.25, "wav"))
        assertNull(AndroidNativeTtsEngine.validateSpeechRequest("Hola", 4.0, "wav"))
        assertTrue(AndroidNativeTtsEngine.validateSpeechRequest("Hola", 0.1, "wav")!!.contains("speed"))
        assertTrue(AndroidNativeTtsEngine.validateSpeechRequest("Hola", 4.5, "wav")!!.contains("speed"))
    }

    @Test
    fun validateSpeechRequest_rejectsFormatsWithoutLocalEncoder() {
        val message = AndroidNativeTtsEngine.validateSpeechRequest("Hola", null, "mp3")
        assertTrue(message!!.contains("mp3") && message.contains("wav, pcm"))
    }

    @Test
    fun validateSpeechRequest_acceptsAac() {
        assertNull(AndroidNativeTtsEngine.validateSpeechRequest("Hola", null, "aac"))
    }

    @Test
    fun splitSpeechInput_keepsShortTextAsOnePiece() {
        assertEquals(listOf("Hola. Qué tal?"), AndroidNativeTtsEngine.splitSpeechInput("Hola. Qué tal?", 100))
    }

    @Test
    fun splitSpeechInput_packsSentencesUpToTheLimit() {
        val pieces = AndroidNativeTtsEngine.splitSpeechInput("Uno. Dos. Tres. Cuatro.", 12)
        assertEquals(listOf("Uno. Dos.", "Tres.", "Cuatro."), pieces)
    }

    @Test
    fun splitSpeechInput_cutsLongSentenceAtSpaces() {
        val text = "palabra ".repeat(10).trim()
        val pieces = AndroidNativeTtsEngine.splitSpeechInput(text, 20)
        assertTrue(pieces.all { it.length <= 20 })
        assertEquals(text.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun splitSpeechInput_blankTextYieldsNoPieces() {
        assertTrue(AndroidNativeTtsEngine.splitSpeechInput("  \n ", 50).isEmpty())
    }

    @Test
    fun chooseVoiceName_exactDeviceVoiceNameWins() {
        assertEquals("en-us-x-local", AndroidNativeTtsEngine.chooseVoiceName(voices, "en-us-x-local", "es-ES"))
    }

    @Test
    fun chooseVoiceName_openAiVoiceNameUsesDefaultLanguage() {
        assertEquals("es-es-x-local", AndroidNativeTtsEngine.chooseVoiceName(voices, "alloy", "es-ES"))
        assertEquals("es-es-x-local", AndroidNativeTtsEngine.chooseVoiceName(voices, null, "es-ES"))
    }

    @Test
    fun chooseVoiceName_bcp47TagSelectsThatLanguage() {
        assertEquals("en-us-x-local", AndroidNativeTtsEngine.chooseVoiceName(voices, "en_US", "es-ES"))
    }

    @Test
    fun chooseVoiceName_fallsBackToSameLanguageThenNull() {
        assertEquals("es-es-x-local", AndroidNativeTtsEngine.chooseVoiceName(voices, "es-MX", "es-ES"))
        assertNull(AndroidNativeTtsEngine.chooseVoiceName(voices, "fr-FR", "es-ES"))
    }

    @Test
    fun encodeSpeechPcm_wavKeepsNativeRateInHeader() {
        val pcm = ByteArray(200)
        val wav = AndroidNativeTtsEngine.encodeSpeechPcm(pcm, sampleRate = 16_000, format = "wav")
        assertEquals(44 + pcm.size, wav.size)
        assertEquals(16_000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
    }

    @Test
    fun encodeSpeechPcm_pcmIsResampledTo24kHz() {
        val pcm = ByteArray(200) // 100 samples at 16 kHz
        val out = AndroidNativeTtsEngine.encodeSpeechPcm(pcm, sampleRate = 16_000, format = "pcm")
        assertEquals(150 * 2, out.size) // 150 samples at 24 kHz, raw (no header)
    }

    @Test
    fun encodeSpeechPcm_pcmAt24kHzIsUnchanged() {
        val pcm = ByteArray(200) { it.toByte() }
        assertTrue(pcm.contentEquals(AndroidNativeTtsEngine.encodeSpeechPcm(pcm, 24_000, "pcm")))
    }
}
