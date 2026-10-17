package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AacAdtsEncoderTest {

    @Test
    fun adtsHeader_hasSyncwordAndAacLcAt24kHzMono() {
        val header = AacAdtsEncoder.adtsHeader(frameLength = 100, sampleRateHz = 24_000, channels = 1)
        assertEquals(7, header.size)
        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xF1.toByte(), header[1])
        // profile AAC-LC (1), sampling index 6 (24 kHz), channel config 1 split across bytes 2 and 3
        assertEquals(0x58.toByte(), header[2])
        assertEquals(0x40.toByte(), header[3])
    }

    @Test
    fun adtsHeader_encodesFullFrameLengthIncludingHeader() {
        val payload = 100
        val header = AacAdtsEncoder.adtsHeader(frameLength = payload, sampleRateHz = 24_000, channels = 1)
        val decoded = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)
        assertEquals(payload + 7, decoded)
    }

    @Test
    fun adtsHeader_rejectsRatesWithoutAdtsIndex() {
        assertThrows(IllegalArgumentException::class.java) {
            AacAdtsEncoder.adtsHeader(frameLength = 10, sampleRateHz = 23_000, channels = 1)
        }
    }
}
