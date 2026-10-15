package com.google.ai.edge.gallery.server.openai

/**
 * Lets normal text stream through while holding back anything that could be (the start of) a
 * text-encoded tool call (see [ToolCallTextParser.MARKERS]), so the call can be parsed at the end
 * without delaying the rest of the answer.
 */
class ToolCallStreamFilter(private val markers: List<String> = ToolCallTextParser.MARKERS) {
  private val held = StringBuilder()
  private var inBlock = false
  private val longest = markers.maxOf { it.length }

  /** Feeds a delta and returns the text that is safe to stream now. */
  fun feed(delta: String): String {
    held.append(delta)
    if (inBlock) return ""
    val open = markers.mapNotNull { m -> held.indexOf(m).takeIf { it >= 0 } }.minOrNull()
    if (open != null) {
      inBlock = true
      val safe = held.substring(0, open)
      held.delete(0, open)
      return safe
    }
    // Keep the longest tail that is still a prefix of some marker.
    var keep = 0
    for (n in minOf(longest - 1, held.length) downTo 1) {
      val tail = held.substring(held.length - n)
      if (markers.any { it.startsWith(tail) }) {
        keep = n
        break
      }
    }
    val safe = held.substring(0, held.length - keep)
    held.delete(0, held.length - keep)
    return safe
  }

  /** The text still held back (starting at the first marker if one was seen). */
  fun finish(): String = held.toString().also { held.clear() }
}
