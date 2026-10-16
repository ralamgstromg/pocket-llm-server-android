package com.google.ai.edge.gallery.server

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionFormatsTest {

    private val segments = listOf(
        SttSegment(start = 0.0, end = 2.5, text = "Hola."),
        SttSegment(start = 2.5, end = 3725.042, text = "Adiós mundo."),
    )

    @Test
    fun formatTimestamp_usesCommaForSrtAndDotForVtt() {
        assertEquals("00:00:02,500", formatTimestamp(2.5, ','))
        assertEquals("00:00:02.500", formatTimestamp(2.5, '.'))
    }

    @Test
    fun formatTimestamp_carriesHoursMinutesAndRoundsMilliseconds() {
        assertEquals("01:02:05.042", formatTimestamp(3725.042, '.'))
        assertEquals("00:00:00.000", formatTimestamp(-1.0, '.'))
    }

    @Test
    fun formatSrt_numbersCuesFromOne() {
        assertEquals(
            "1\n00:00:00,000 --> 00:00:02,500\nHola.\n\n" +
                "2\n00:00:02,500 --> 01:02:05,042\nAdiós mundo.\n\n",
            formatSrt(segments),
        )
    }

    @Test
    fun formatVtt_startsWithHeader() {
        assertEquals(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:02.500\nHola.\n\n" +
                "00:00:02.500 --> 01:02:05.042\nAdiós mundo.\n\n",
            formatVtt(segments),
        )
    }

    @Test
    fun formatSrtAndVtt_emptySegments() {
        assertEquals("", formatSrt(emptyList()))
        assertEquals("WEBVTT\n\n", formatVtt(emptyList()))
    }
}
