package com.google.ai.edge.gallery.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PocketNodeLoggerTest {

    @Before
    fun setUp() {
        PocketNodeLogger.clear()
        PocketNodeLogger.setLoggingEnabled(null, true)
    }

    @After
    fun tearDown() {
        PocketNodeLogger.clear()
        PocketNodeLogger.setLoggingEnabled(null, true)
    }

    @Test
    fun log_recordsEntriesWhenEnabled_andFormatsLine() {
        PocketNodeLogger.log(
            method = "POST",
            route = "/v1/chat/completions",
            statusCode = 200,
            durationMs = 450L,
            model = "Gemma-4-E2B-it",
            detail = "prompt=15 chars, reply=ok",
            timestamp = "2026-10-16 13:00:00.000",
        )

        assertEquals(1, PocketNodeLogger.entries.size)
        val line = PocketNodeLogger.entries.first().formatLine()
        assertEquals(
            "[2026-10-16 13:00:00.000] POST /v1/chat/completions -> HTTP 200 (450ms) | model=Gemma-4-E2B-it | prompt=15 chars, reply=ok",
            line,
        )
    }

    @Test
    fun log_ignoresEntriesWhenDisabled() {
        PocketNodeLogger.setLoggingEnabled(null, false)
        assertFalse(PocketNodeLogger.isLoggingEnabled)

        PocketNodeLogger.log(
            method = "GET",
            route = "/",
            statusCode = 200,
            durationMs = 5L,
            detail = "Health check OK",
        )

        assertTrue(PocketNodeLogger.entries.isEmpty())
    }

    @Test
    fun formatExportText_includesHeaderAndAllEntries() {
        PocketNodeLogger.log(
            method = "GET",
            route = "/",
            statusCode = 200,
            durationMs = 3L,
            detail = "Health check OK",
            timestamp = "2026-10-16 13:01:00.000",
        )
        PocketNodeLogger.log(
            method = "POST",
            route = "/v1/audio/transcriptions",
            statusCode = 200,
            durationMs = 1200L,
            model = "Whisper-Large-V3-Turbo-i4",
            detail = "lang=es, text=Hola",
            timestamp = "2026-10-16 13:01:02.000",
        )

        val exported = PocketNodeLogger.formatExportText()
        assertTrue(exported.contains("# Total operaciones: 2"))
        assertTrue(exported.contains("GET / -> HTTP 200 (3ms)"))
        assertTrue(exported.contains("model=Whisper-Large-V3-Turbo-i4"))
    }
}
