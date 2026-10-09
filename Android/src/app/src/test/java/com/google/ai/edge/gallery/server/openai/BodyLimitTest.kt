package com.google.ai.edge.gallery.server.openai

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BodyLimitTest {
  @Test
  fun readsBodyWithinLimit() = runBlocking {
    val bytes = ByteArray(100) { it.toByte() }
    assertArrayEquals(bytes, BodyLimit.readLimited(ByteReadChannel(bytes), maxBytes = 100))
  }

  @Test
  fun rejectsBodyOverLimitWith413() = runBlocking {
    try {
      BodyLimit.readLimited(ByteReadChannel(ByteArray(101)), maxBytes = 100)
      fail("Expected OpenAiException")
    } catch (e: OpenAiException) {
      assertEquals(413, e.status)
    }
  }
}
