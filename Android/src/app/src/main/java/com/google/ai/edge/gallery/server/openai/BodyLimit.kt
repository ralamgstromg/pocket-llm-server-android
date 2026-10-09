package com.google.ai.edge.gallery.server.openai

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

/** Reads request bodies with a hard size cap, also when no Content-Length is sent (chunked). */
object BodyLimit {
  suspend fun readLimited(channel: ByteReadChannel, maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
      val n = channel.readAvailable(buf, 0, buf.size)
      if (n == -1) break
      if (n == 0) {
        if (channel.isClosedForRead) break
        channel.awaitContent()
        continue
      }
      if (out.size().toLong() + n > maxBytes) {
        throw OpenAiException(413, "Request body exceeds ${maxBytes / (1024 * 1024)} MB.", code = "request_too_large")
      }
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }
}
