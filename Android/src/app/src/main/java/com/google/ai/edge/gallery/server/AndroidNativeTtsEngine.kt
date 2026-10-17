package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown when too many syntheses are already queued. Maps to HTTP 429. */
class TtsBusyException : IllegalStateException("Servicio TTS ocupado: demasiadas síntesis en cola. Reintente en unos segundos.")

data class TtsOutput(val audio: ByteArray, val voiceName: String, val timings: String)

/** A device voice that can be used offline, reduced to the fields voice selection needs. */
data class VoiceCandidate(val name: String, val localeTag: String)

/**
 * Text-to-Speech powered by the phone's own TTS engine ([TextToSpeech]). Mirrors [AndroidNativeSttEngine]:
 * - Only voices that work offline are used, so no network is needed.
 * - Synthesizes each sentence group to a temporary WAV file and joins the PCM. Output is `wav` at the
 *   engine's native rate, or raw `pcm` at 24 kHz (OpenAI's format). Other formats are rejected.
 * - One engine instance is kept warm and released by [shutdown] when the server hibernates.
 */
object AndroidNativeTtsEngine {

    const val ENGINE_MODEL_ID = "Android-Native-TTS"
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    const val MAX_INPUT_CHARS = 4096
    const val MIN_SPEED = 0.25
    const val MAX_SPEED = 4.0
    val SUPPORTED_FORMATS = setOf("wav", "pcm", "aac")

    private const val TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE"
    private const val MAX_CHUNK_CHARS = 3500
    private const val MAX_PENDING_REQUESTS = 4
    private const val INIT_TIMEOUT_MS = 10_000L
    private const val CHUNK_TIMEOUT_MS = 60_000L
    private const val NO_LOCAL_VOICE_MESSAGE =
        "No hay voz local instalada para el idioma solicitado. Instale la voz sin conexión en Ajustes > Salida de texto a voz."

    private val OPENAI_VOICE_NAMES = setOf("alloy", "echo", "fable", "onyx", "nova", "shimmer", "ash", "coral", "sage")
    private val LOCALE_PATTERN = Regex("[a-zA-Z]{2,3}[-_][a-zA-Z0-9]{2,8}(?:[-_][a-zA-Z0-9]{2,8})*")
    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?…])\\s+|\\n+")

    private val ttsMutex = Mutex()
    private val pendingRequests = AtomicInteger(0)

    /** Only touched on the main thread, where [TextToSpeech] delivers its callbacks. */
    private var engine: TextToSpeech? = null

    /**
     * Returns null if the input is acceptable, otherwise the client-facing error message.
     * Speed follows OpenAI's 0.25..4.0 range.
     */
    internal fun validateSpeechRequest(input: String, speed: Double?, format: String): String? = when {
        input.isBlank() -> "El campo 'input' es obligatorio."
        input.length > MAX_INPUT_CHARS -> "El campo 'input' admite hasta $MAX_INPUT_CHARS caracteres."
        speed != null && (speed < MIN_SPEED || speed > MAX_SPEED) -> "'speed' debe estar entre $MIN_SPEED y $MAX_SPEED."
        format !in SUPPORTED_FORMATS -> "response_format '$format' no soportado en el móvil. Soportados: ${SUPPORTED_FORMATS.joinToString()}."
        else -> null
    }

    /**
     * Splits [text] into pieces of at most [maxChars] at sentence boundaries, packing sentences together.
     * A sentence longer than [maxChars] is cut at the last space before the limit.
     */
    internal fun splitSpeechInput(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) pieces += current.toString().trim()
            current.clear()
        }
        for (sentence in text.split(SENTENCE_BOUNDARY)) {
            var rest = sentence.trim()
            while (rest.length > maxChars) {
                val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                flush()
                pieces += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isEmpty()) continue
            if (current.isNotEmpty() && current.length + 1 + rest.length > maxChars) flush()
            if (current.isNotEmpty()) current.append(' ')
            current.append(rest)
        }
        flush()
        return pieces
    }

    /**
     * Picks a voice name from [voices]. An exact device voice name wins. An OpenAI voice name (`alloy`,
     * `nova`, ...) or no name uses the default language. A BCP-47 tag (`en-US`) selects that language.
     * Falls back to any voice of the same language. Returns null when no voice matches.
     */
    internal fun chooseVoiceName(voices: List<VoiceCandidate>, requested: String?, defaultLocaleTag: String): String? {
        val request = requested?.trim().orEmpty()
        if (request.isNotEmpty() && request.lowercase(Locale.ROOT) !in OPENAI_VOICE_NAMES) {
            voices.firstOrNull { it.name.equals(request, ignoreCase = true) }?.let { return it.name }
        }
        val wantedLocale = if (LOCALE_PATTERN.matches(request)) {
            AndroidNativeSttEngine.normalizeLocaleTag(request)
        } else {
            defaultLocaleTag
        }
        val wantedLanguage = wantedLocale.substringBefore('-')
        return voices.firstOrNull { it.localeTag.equals(wantedLocale, ignoreCase = true) }?.name
            ?: voices.firstOrNull { it.localeTag.substringBefore('-').equals(wantedLanguage, ignoreCase = true) }?.name
    }

    /**
     * Encodes 16-bit mono PCM from the engine. `wav` keeps the engine's [sampleRate] with a RIFF header.
     * `pcm` is raw little-endian samples resampled to [OUTPUT_SAMPLE_RATE_HZ]. `aac` is ADTS-framed AAC-LC
     * at the engine's rate. It is blocking, so call it from a background dispatcher.
     */
    internal fun encodeSpeechPcm(pcm: ByteArray, sampleRate: Int, format: String): ByteArray = when (format) {
        "pcm" -> samplesToPcmBytes(AudioDecoderHelper.resampleMono(pcmBytesToSamples(pcm), sampleRate, OUTPUT_SAMPLE_RATE_HZ))
        "aac" -> AacAdtsEncoder.encodeAdts(pcm, sampleRate)
        else -> AudioDecoderHelper.createWavFileBytes(pcmBytesToSamples(pcm), sampleRate)
    }

    private fun pcmBytesToSamples(pcm: ByteArray): ShortArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    private fun samplesToPcmBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    /** Returns a human-readable description of the phone's native TTS availability. */
    fun statusSummary(context: Context): String {
        val hasEngine = runCatching {
            context.packageManager.queryIntentServices(Intent(TTS_SERVICE_ACTION), 0).isNotEmpty()
        }.getOrDefault(false)
        return if (hasEngine) {
            "Servicio TTS del móvil (motor del sistema activo)"
        } else {
            "Sin motor TTS del sistema (instale o active un motor de voz)"
        }
    }

    /**
     * Synthesizes [input] with the phone's TTS engine and encodes it as [format] (`wav` or `pcm`).
     * Safe to invoke while the screen is off.
     */
    suspend fun synthesize(
        context: Context,
        input: String,
        voice: String?,
        speed: Float,
        format: String,
    ): Result<TtsOutput> {
        if (pendingRequests.incrementAndGet() > MAX_PENDING_REQUESTS) {
            pendingRequests.decrementAndGet()
            return Result.failure(TtsBusyException())
        }
        try {
            return ttsMutex.withLock {
                synthesizeExclusive(context.applicationContext, input, voice, speed, format)
            }
        } finally {
            pendingRequests.decrementAndGet()
        }
    }

    private suspend fun synthesizeExclusive(
        appContext: Context,
        input: String,
        voice: String?,
        speed: Float,
        format: String,
    ): Result<TtsOutput> {
        PocketNodeInference.touchActivity(appContext)
        return PocketNodeInference.withInferenceWakeLock(appContext) {
            val startMs = System.currentTimeMillis()
            val tts = runCatching { ensureEngine(appContext) }.getOrElse { e ->
                return@withInferenceWakeLock Result.failure(IllegalStateException("Motor TTS del móvil no disponible: ${e.message}"))
            }

            val localVoices = tts.voices.orEmpty().filter { isLocalVoice(it) }
            val voiceName = chooseVoiceName(
                voices = localVoices.map { VoiceCandidate(it.name, it.locale.toLanguageTag()) },
                requested = voice,
                defaultLocaleTag = AndroidNativeSttEngine.normalizeLocaleTag(PocketNodeInference.DEFAULT_LANGUAGE),
            ) ?: return@withInferenceWakeLock Result.failure(IllegalStateException(NO_LOCAL_VOICE_MESSAGE))
            tts.setVoice(localVoices.first { it.name == voiceName })
            tts.setSpeechRate(speed)

            val chunks = splitSpeechInput(input, minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK_CHARS))
            if (chunks.isEmpty()) {
                return@withInferenceWakeLock Result.failure(IllegalArgumentException("El campo 'input' es obligatorio."))
            }
            val cacheDir = File(appContext.cacheDir, "tts").apply { mkdirs() }
            val pcmOut = ByteArrayOutputStream()
            var sampleRate = 0
            chunks.forEachIndexed { index, chunk ->
                val file = File(cacheDir, "chunk-${System.nanoTime()}-$index.wav")
                try {
                    synthesizeChunk(tts, chunk, file, "pn-${System.nanoTime()}-$index").onFailure {
                        return@withInferenceWakeLock Result.failure(it)
                    }
                    val (rate, pcm) = AudioDecoderHelper.parseWavPcm16Mono(
                        runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                    ) ?: return@withInferenceWakeLock Result.failure(
                        IllegalStateException("El motor TTS devolvió un audio que no es WAV PCM16 mono.")
                    )
                    if (sampleRate == 0) sampleRate = rate
                    pcmOut.write(
                        if (rate == sampleRate) {
                            pcm
                        } else {
                            samplesToPcmBytes(AudioDecoderHelper.resampleMono(pcmBytesToSamples(pcm), rate, sampleRate))
                        }
                    )
                } finally {
                    file.delete()
                }
            }

            val pcm = pcmOut.toByteArray()
            if (pcm.isEmpty()) {
                return@withInferenceWakeLock Result.failure(IllegalStateException("El motor TTS no generó audio."))
            }
            val audioSeconds = pcm.size.toFloat() / (sampleRate * 2).toFloat()
            Result.success(
                TtsOutput(
                    audio = encodeSpeechPcm(pcm, sampleRate, format),
                    voiceName = voiceName,
                    timings = "synth=${System.currentTimeMillis() - startMs}ms chunks=${chunks.size} " +
                        "audio=${"%.1f".format(Locale.US, audioSeconds)}s format=$format",
                )
            )
        }
    }

    private fun isLocalVoice(voice: Voice): Boolean =
        !voice.isNetworkConnectionRequired &&
            !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    /** Creates the engine on first use and keeps it warm until [shutdown]. */
    private suspend fun ensureEngine(appContext: Context): TextToSpeech = withContext(Dispatchers.Main.immediate) {
        engine ?: withTimeout(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                lateinit var created: TextToSpeech
                created = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS && cont.isActive) {
                        engine = created
                        cont.resume(created)
                    } else {
                        created.shutdown()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("código de estado $status"))
                    }
                }
            }
        }
    }

    private suspend fun synthesizeChunk(
        tts: TextToSpeech,
        text: String,
        file: File,
        utteranceId: String,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                fun finish(result: Result<Unit>) {
                    if (cont.isActive) cont.resume(result)
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) finish(Result.success(Unit))
                    }
                    override fun onError(id: String?) {
                        if (id == utteranceId) finish(Result.failure(IllegalStateException("Error en el motor TTS del móvil.")))
                    }
                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            finish(Result.failure(IllegalStateException("Error en el motor TTS del móvil (código $errorCode).")))
                        }
                    }
                })
                if (tts.synthesizeToFile(text, null, file, utteranceId) != TextToSpeech.SUCCESS) {
                    finish(Result.failure(IllegalStateException("El motor TTS del móvil rechazó la síntesis.")))
                }
            }
        } ?: Result.failure(IllegalStateException("Tiempo de espera agotado en el motor TTS del móvil."))
    }

    /** Releases the engine. Waits for any in-flight synthesis so the callbacks are not cut short. */
    suspend fun shutdown() {
        ttsMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                engine?.shutdown()
                engine = null
            }
        }
    }
}
