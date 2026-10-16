package com.google.ai.edge.gallery.server

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderHelperTest {

    private fun wav(sampleRate: Int, channels: Int, bits: Int, data: ByteArray): ByteArray {
        val blockAlign = channels * bits / 8
        return ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort(blockAlign.toShort()).putShort(bits.toShort())
            .put("data".toByteArray()).putInt(data.size).put(data)
            .array()
    }

    @Test
    fun extractWavPcm16kMonoBytes_returnsRawPcmOfA16kMonoWav() {
        val pcm = byteArrayOf(1, 0, 2, 0)
        assertArrayEquals(pcm, AudioDecoderHelper.extractWavPcm16kMonoBytes(wav(16_000, 1, 16, pcm)))
    }

    @Test
    fun extractWavPcm16kMonoBytes_rejectsOtherFormats() {
        val pcm = ByteArray(8)
        assertNull(AudioDecoderHelper.extractWavPcm16kMonoBytes(wav(44_100, 1, 16, pcm)))
        assertNull(AudioDecoderHelper.extractWavPcm16kMonoBytes(wav(16_000, 2, 16, pcm)))
        assertNull(AudioDecoderHelper.extractWavPcm16kMonoBytes(wav(16_000, 1, 8, pcm)))
    }

    @Test
    fun extractWavPcm16kMonoBytes_rejectsNonWavInput() {
        assertNull(AudioDecoderHelper.extractWavPcm16kMonoBytes("not a wav file at all".toByteArray()))
    }

    @Test
    fun isPcmSilence_trueForZerosAndNoiseFloor() {
        assertTrue(AudioDecoderHelper.isPcmSilence(ByteArray(3_200)))
        val floor = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(100).putShort((-100).toShort()).array()
        assertTrue(AudioDecoderHelper.isPcmSilence(floor))
    }

    @Test
    fun isPcmSilence_falseWhenAnySampleIsAboveThreshold() {
        val speech = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(5_000).array()
        assertFalse(AudioDecoderHelper.isPcmSilence(speech))
    }
}
