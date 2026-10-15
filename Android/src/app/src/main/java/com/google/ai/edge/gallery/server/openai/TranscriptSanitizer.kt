package com.google.ai.edge.gallery.server.openai

/** Cleans a model's transcription answer: strips reasoning, code fences, intro labels and quotes. */
object TranscriptSanitizer {
  fun sanitize(rawInput: String): String {
    var text = rawInput
    // Qwen3-ASR writes "language Spanish<asr_text>transcript".
    text = text.substringAfter("<asr_text>", text)
    // 1. Remove thinking / reasoning blocks <think>...</think>
    text = text.replace(Regex("(?s)<think>.*?</think>"), "")
    text = text.replace(Regex("(?s)<transcript>.*?</transcript>"), "")

    // 2. Remove code blocks ```text ... ```
    text = text.replace(Regex("(?s)```[a-zA-Z]*\\s*(.*?)\\s*```"), "$1")

    // 3. Remove common intro prefixes
    val prefixRegex =
      Regex(
        "(?i)^\\s*((" +
          "here is the transcription|" +
          "here is the transcribed text|" +
          "transcription|" +
          "transcripci[oó]n|" +
          "el usuario dijo|" +
          "el audio dice|" +
          "the user said|" +
          "the speaker said|" +
          "spoken text|" +
          "texto hablado|" +
          "text" +
          ")\\s*:\\s*)"
      )
    text = text.replace(prefixRegex, "")

    // 4. Strip surrounding quotes and whitespace
    text = text.trim()
    if (
      (text.startsWith("\"") && text.endsWith("\"")) ||
        (text.startsWith("'") && text.endsWith("'")) ||
        (text.startsWith("«") && text.endsWith("»"))
    ) {
      text = text.substring(1, text.length - 1).trim()
    }
    return text
  }

  private val LANGUAGE_PREFIX = Regex("(?s)^\\s*language\\s+([A-Za-z]+)\\s*<asr_text>")

  /** The language name a Qwen3-ASR answer announced ("Spanish"), or null. */
  fun detectedLanguage(raw: String): String? = LANGUAGE_PREFIX.find(raw)?.groupValues?.get(1)
}
