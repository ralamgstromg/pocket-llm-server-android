package com.google.ai.edge.gallery.server.whisper

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperFeatureExtractorTest {

    // 1 s of a 440 Hz tone at 0.5 amplitude, 16 kHz. Reference values come from
    // transformers.WhisperFeatureExtractor(feature_size=128) on the same signal.
    private val tone = FloatArray(16000) { (0.5 * sin(2 * Math.PI * 440.0 * it / 16000.0)).toFloat() }

    @Test
    fun extract_returnsFixed30sWindowOf128Mels() {
        val mel = WhisperFeatureExtractor.extract(tone)
        assertEquals(WhisperFeatureExtractor.N_MELS * WhisperFeatureExtractor.N_FRAMES, mel.size)
    }

    @Test
    fun extract_matchesTransformersReference() {
        val mel = WhisperFeatureExtractor.extract(tone)
        val frames = WhisperFeatureExtractor.N_FRAMES
        fun at(m: Int, f: Int) = mel[m * frames + f]

        val expectedFirstFrame = floatArrayOf(0.907520f, 0.395512f, -0.514646f, -0.514646f, -0.514646f)
        for (f in expectedFirstFrame.indices) assertClose(expectedFirstFrame[f], at(0, f))
        assertClose(-0.514646f, at(10, 500))
        assertClose(0.263159f, at(64, 100))
        assertClose(-0.514646f, at(127, 2999))
        assertClose(-0.514646f, at(127, 10))

        val sum = mel.sumOf { it.toDouble() }
        assertTrue("sum=$sum", abs(sum - (-196327.062)) < 1.0)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) < 1e-3f)
    }
}
