package com.google.ai.edge.gallery.server.openai

/**
 * Splits streamed model text into visible content and reasoning, moving `<think>...</think>`
 * blocks to reasoning. Tags may be split across deltas; partial tags are held back.
 *
 * With [assumeThinkingFirst] (thinking requested, the chat template may have pre-filled `<think>`),
 * text is held until `</think>` is seen and then reported as reasoning; if the stream ends without
 * it, the held text is returned as content.
 */
class ThinkStreamSplitter(assumeThinkingFirst: Boolean) {
  private val pending = StringBuilder()
  private var inThink = assumeThinkingFirst
  private var holdUntilClose = assumeThinkingFirst

  /** Returns (content, reasoning) that are safe to emit. */
  fun feed(delta: String): Pair<String, String> {
    pending.append(delta)
    val content = StringBuilder()
    val reasoning = StringBuilder()
    while (true) {
      val tag = if (inThink) CLOSE else OPEN
      val idx = pending.indexOf(tag)
      if (idx >= 0) {
        val before = pending.substring(0, idx)
        if (inThink) reasoning.append(before) else content.append(before)
        pending.delete(0, idx + tag.length)
        inThink = !inThink
        holdUntilClose = false
        continue
      }
      if (holdUntilClose) return Pair("", "")
      // Keep a possible partial tag at the end of the buffer.
      val keep = partialTagSuffix(pending, tag)
      val emit = pending.substring(0, pending.length - keep)
      if (inThink) reasoning.append(emit) else content.append(emit)
      pending.delete(0, pending.length - keep)
      return Pair(content.toString(), reasoning.toString())
    }
  }

  /** Returns whatever is still held back once the stream has ended. */
  fun flush(): Pair<String, String> {
    val rest = pending.toString()
    pending.clear()
    return if (inThink && !holdUntilClose) Pair("", rest) else Pair(rest, "")
  }

  private fun partialTagSuffix(buf: CharSequence, tag: String): Int {
    for (len in minOf(tag.length - 1, buf.length) downTo 1) {
      if (buf.endsWith(tag.substring(0, len))) return len
    }
    return 0
  }

  private companion object {
    const val OPEN = "<think>"
    const val CLOSE = "</think>"
  }
}
