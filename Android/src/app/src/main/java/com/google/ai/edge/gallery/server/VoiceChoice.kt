package com.google.ai.edge.gallery.server

/** A voice of the system speech engine, reduced to what voice selection needs. */
data class VoiceInfo(val name: String, val languageTag: String, val quality: Int, val networkRequired: Boolean)

/** Picks the system voice for a `/v1/audio/speech` request. */
object VoiceChoice {
  /** OpenAI voices, in the order they are spread across the voices of the language. */
  val OPENAI_VOICES = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")

  /**
   * [requested] may be a system voice name, an OpenAI voice name, or empty. The result is always
   * a voice in the configured language when one exists: an exact name wins, otherwise the OpenAI
   * name picks the same voice every time among the best offline voices of [languageTag].
   */
  fun pick(voices: List<VoiceInfo>, requested: String?, languageTag: String): VoiceInfo? {
    requested?.let { r -> voices.find { it.name.equals(r, ignoreCase = true) }?.let { return it } }
    val lang = languageTag.substringBefore('-').substringBefore('_').lowercase()
    val region = languageTag.replace('_', '-').substringAfter('-', "").lowercase()
    val inLanguage = voices.filter { it.languageTag.replace('_', '-').lowercase().substringBefore('-') == lang }
    if (inLanguage.isEmpty()) return null
    val ranked =
      inLanguage.sortedWith(
        compareBy<VoiceInfo> { it.networkRequired }
          .thenByDescending { region.isNotEmpty() && it.languageTag.replace('_', '-').lowercase().endsWith(region) }
          .thenByDescending { it.quality }
          .thenBy { it.name }
      )
    val index = OPENAI_VOICES.indexOf(requested?.lowercase()).takeIf { it >= 0 } ?: 0
    return ranked[index % ranked.size]
  }
}
