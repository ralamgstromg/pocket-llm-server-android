package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeSttEngineTest {

    @Test
    fun normalizeLocaleTag_mapsIsoCodesToFullLocales() {
        assertEquals("es-ES", AndroidNativeSttEngine.normalizeLocaleTag("es"))
        assertEquals("en-US", AndroidNativeSttEngine.normalizeLocaleTag("en"))
        assertEquals("pt-BR", AndroidNativeSttEngine.normalizeLocaleTag("pt"))
    }

    @Test
    fun normalizeLocaleTag_keepsBcp47AndDefaultsBlankOrAutoToSpanish() {
        assertEquals("en-GB", AndroidNativeSttEngine.normalizeLocaleTag("en_GB"))
        assertEquals("es-ES", AndroidNativeSttEngine.normalizeLocaleTag(""))
        assertEquals("es-ES", AndroidNativeSttEngine.normalizeLocaleTag("auto"))
        assertEquals("es-ES", AndroidNativeSttEngine.normalizeLocaleTag(null))
    }

    @Test
    fun splitPcmIntoSegments_shortAudioIsOneSegment() {
        val pcm = ByteArray(64_000)
        val segments = AndroidNativeSttEngine.splitPcmIntoSegments(pcm)
        assertEquals(1, segments.size)
        assertEquals(pcm.size, segments[0].size)
    }

    @Test
    fun splitPcmIntoSegments_dropsTrailingOddByte() {
        val segments = AndroidNativeSttEngine.splitPcmIntoSegments(ByteArray(5))
        assertEquals(1, segments.size)
        assertEquals(4, segments[0].size)
    }

    @Test
    fun splitPcmIntoSegments_longAudioSplitsContiguouslyWithinLimit() {
        val total = 60 * AndroidNativeSttEngine.BYTES_PER_SECOND // 60 s
        val pcm = ByteArray(total) { (it % 251).toByte() }
        val segments = AndroidNativeSttEngine.splitPcmIntoSegments(pcm)

        assertTrue(segments.size >= 3)
        assertTrue(segments.all { it.size <= AndroidNativeSttEngine.MAX_SEGMENT_BYTES })
        assertEquals(total, segments.sumOf { it.size })
        // Segments must be contiguous: concatenation reproduces the input byte-for-byte.
        assertTrue(segments.reduce { acc, bytes -> acc + bytes }.contentEquals(pcm))
    }

    @Test
    fun splitPcmIntoSegments_emptyInputYieldsNoSegments() {
        assertTrue(AndroidNativeSttEngine.splitPcmIntoSegments(ByteArray(0)).isEmpty())
    }
}
