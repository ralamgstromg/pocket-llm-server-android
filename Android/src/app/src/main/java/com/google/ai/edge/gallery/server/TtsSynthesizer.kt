package com.google.ai.edge.gallery.server

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.ai.edge.gallery.server.openai.OpenAiException
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Text-to-speech through the Android system engine (`/v1/audio/speech`). Output is WAV. */
object TtsSynthesizer {
  private const val TIMEOUT_MS = 60_000L

  private fun infos(tts: TextToSpeech) =
    tts.voices.orEmpty().map { VoiceInfo(it.name, it.locale.toLanguageTag(), it.quality, it.isNetworkConnectionRequired) }

  /** Voices of the system engine, for `GET /v1/audio/voices`. */
  suspend fun voices(context: Context): List<VoiceInfo> {
    val tts = open(context)
    return try {
      infos(tts).sortedWith(compareBy({ it.languageTag }, { it.name }))
    } finally {
      tts.shutdown()
    }
  }

  /**
   * Speaks [text] to a temporary WAV file. [voice] is a system voice name; anything else (OpenAI
   * voices such as "alloy") uses the language configured in the server settings.
   */
  suspend fun synthesize(context: Context, text: String, voice: String?, speed: Double?): File {
    val tts = open(context)
    try {
      val tag = PocketNodeState.ttsLanguage.ifEmpty { Locale.getDefault().toLanguageTag() }
      val match = VoiceChoice.pick(infos(tts), voice, tag)?.let { chosen -> tts.voices.orEmpty().find { it.name == chosen.name } }
      if (match != null) {
        tts.voice = match
      } else {
        val locale = Locale.forLanguageTag(tag)
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
          throw OpenAiException(503, "The system text-to-speech engine has no voice for '$locale'.", code = "tts_language_unavailable")
        }
      }
      tts.setSpeechRate(((speed ?: 1.0) * PocketNodeState.ttsRate).toFloat().coerceIn(0.25f, 4.0f))

      val file = File(context.cacheDir, "tts_${UUID.randomUUID()}.wav")
      val id = UUID.randomUUID().toString()
      val ok =
        withTimeoutOrNull(TIMEOUT_MS) {
          suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(
              object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                  if (cont.isActive) cont.resume(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                  if (cont.isActive) cont.resume(false)
                }
              }
            )
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            cont.invokeOnCancellation { tts.stop() }
          }
        }
      if (ok != true || !file.exists() || file.length() == 0L) {
        file.delete()
        throw OpenAiException(500, "Text-to-speech synthesis failed.", code = "tts_failed")
      }
      return file
    } finally {
      tts.shutdown()
    }
  }

  /** Starts the system engine and waits until it is ready. */
  private suspend fun open(context: Context): TextToSpeech {
    var created: TextToSpeech? = null
    val ready =
      withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { cont ->
          created = TextToSpeech(context.applicationContext) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
        }
      }
    val tts = created
    if (ready != true || tts == null) {
      tts?.shutdown()
      throw OpenAiException(503, "The system text-to-speech engine is not available.", code = "tts_unavailable")
    }
    return tts
  }
}
