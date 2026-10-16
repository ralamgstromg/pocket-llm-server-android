package com.google.ai.edge.gallery.server.whisper

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import java.io.File
import kotlin.math.ceil
import kotlin.math.min
import org.tensorflow.lite.Interpreter

val Model.isWhisperStt: Boolean
    get() = runtimeType == RuntimeType.TFLITE_WHISPER

/** Wall-clock breakdown of one transcription, in milliseconds. */
data class WhisperTimings(val melMs: Long, val encodeMs: Long, val decodeMs: Long, val decodeSteps: Int) {
    fun toHeaderValue(): String = "mel=${melMs}ms encode=${encodeMs}ms decode=${decodeMs}ms steps=$decodeSteps"
}

/**
 * Speech-to-text with a Whisper LiteRT export (`encode` + `decode` signatures, 30 s fixed window).
 * Greedy decoding without KV cache: the decoder re-runs the full fixed-length sequence each step.
 * Not thread-safe; callers must serialize [transcribe].
 */
class WhisperEngine private constructor(
    private val interpreter: Interpreter,
    private val tokenizer: WhisperTokenizer,
) : AutoCloseable {

    private val melInput = Array(1) { Array(N_MELS) { FloatArray(N_FRAMES) } }
    private val encoderOut = Array(1) { Array(ENC_FRAMES) { FloatArray(ENC_DIM) } }
    private val tokenInput = Array(1) { IntArray(MAX_LEN) { WhisperTokenizer.EOT } }
    private val logits = Array(1) { Array(MAX_LEN) { FloatArray(VOCAB) } }
    private val causalMask = Array(1) {
        Array(1) { Array(MAX_LEN) { i -> FloatArray(MAX_LEN) { j -> if (j <= i) 0f else NEG_INF } } }
    }

    private var melNanos = 0L
    private var encodeNanos = 0L
    private var decodeNanos = 0L
    private var decodeSteps = 0

    /** Timing breakdown of the last [transcribe] call. */
    var lastTimings: WhisperTimings? = null
        private set

    /**
     * Transcribes 16 kHz mono samples. Audio longer than 30 s is split into windows and the texts
     * are joined. [language] is an ISO code such as "es"; when null or blank it defaults to Spanish ("es"),
     * which also avoids the extra decoder pass of automatic language detection.
     */
    fun transcribe(samples: FloatArray, language: String? = DEFAULT_LANGUAGE): String {
        val effectiveLang = resolveLanguageCode(language)
        val forcedLanguage = effectiveLang?.let { code ->
            val id = tokenizer.idOf("<|$code|>")
            require(id in tokenizer.languageIds) { "Idioma no soportado por Whisper: $code" }
            id
        }

        melNanos = 0L
        encodeNanos = 0L
        decodeNanos = 0L
        decodeSteps = 0
        val texts = mutableListOf<String>()
        var detectedLanguage: Int? = forcedLanguage
        for (start in samples.indices step WhisperFeatureExtractor.N_SAMPLES) {
            val end = min(start + WhisperFeatureExtractor.N_SAMPLES, samples.size)
            encodeWindow(samples.copyOfRange(start, end))
            val languageId = detectedLanguage ?: detectLanguage().also { detectedLanguage = it }
            texts += tokenizer.decode(decodeWindow(languageId, end - start)).trim()
        }
        lastTimings = WhisperTimings(
            melMs = melNanos / 1_000_000,
            encodeMs = encodeNanos / 1_000_000,
            decodeMs = decodeNanos / 1_000_000,
            decodeSteps = decodeSteps,
        )
        return texts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    override fun close() {
        interpreter.close()
    }

    private fun encodeWindow(window: FloatArray) {
        val melStart = System.nanoTime()
        val mel = WhisperFeatureExtractor.extract(window)
        for (m in 0 until N_MELS) {
            System.arraycopy(mel, m * N_FRAMES, melInput[0][m], 0, N_FRAMES)
        }
        melNanos += System.nanoTime() - melStart

        val encodeStart = System.nanoTime()
        interpreter.runSignature(
            mapOf<String, Any>("args_0" to melInput),
            mapOf<String, Any>("output_0" to encoderOut),
            "encode",
        )
        encodeNanos += System.nanoTime() - encodeStart
    }

    private fun detectLanguage(): Int {
        tokenInput[0].fill(WhisperTokenizer.EOT)
        tokenInput[0][0] = WhisperTokenizer.SOT
        runDecoder()
        val row = logits[0][0]
        return tokenizer.languageIds.maxBy { row[it] }
    }

    private fun decodeWindow(languageId: Int, windowSamples: Int): IntArray {
        tokenInput[0].fill(WhisperTokenizer.EOT)
        tokenInput[0][0] = WhisperTokenizer.SOT
        tokenInput[0][1] = languageId
        tokenInput[0][2] = tokenizer.idOf("<|transcribe|>")
        tokenInput[0][3] = tokenizer.idOf("<|notimestamps|>")

        val limit = PROMPT_LENGTH + maxNewTokens(windowSamples)
        var length = PROMPT_LENGTH
        while (length < limit) {
            runDecoder()
            val row = logits[0][length - 1]
            // Only text tokens and end-of-text are allowed; timestamps and other specials are suppressed.
            var next = 0
            for (id in 1..WhisperTokenizer.EOT) {
                if (row[id] > row[next]) next = id
            }
            if (next == WhisperTokenizer.EOT) break
            tokenInput[0][length] = next
            length++
        }
        return tokenInput[0].copyOfRange(PROMPT_LENGTH, length)
    }

    private fun runDecoder() {
        val start = System.nanoTime()
        interpreter.runSignature(
            mapOf<String, Any>(
                "args_0" to encoderOut,
                "args_1" to tokenInput,
                "args_2" to causalMask,
            ),
            mapOf<String, Any>("output_0" to logits),
            "decode",
        )
        decodeNanos += System.nanoTime() - start
        decodeSteps++
    }

    companion object {
        const val DEFAULT_LANGUAGE = "es"
        private const val ENC_FRAMES = 1500
        private const val ENC_DIM = 1280
        private const val VOCAB = 51866
        private const val MAX_LEN = 128
        private const val PROMPT_LENGTH = 4
        private const val NEG_INF = -1e9f
        private const val MIN_THREADS = 4
        private const val MAX_THREADS = 8
        private const val TOKENS_PER_SECOND = 8.0

        private const val N_MELS = WhisperFeatureExtractor.N_MELS
        private const val N_FRAMES = WhisperFeatureExtractor.N_FRAMES

        /** Resolves the language code, defaulting to Spanish ("es") unless "auto" is explicitly requested. */
        internal fun resolveLanguageCode(language: String?): String? {
            val trimmed = language?.trim()?.lowercase()
            return when {
                trimmed.isNullOrEmpty() -> DEFAULT_LANGUAGE
                trimmed == "auto" -> null
                trimmed == "spanish" || trimmed == "español" -> "es"
                else -> trimmed
            }
        }

        /**
         * Decoding budget for one window: about 8 tokens per second of audio plus a margin, capped by
         * the fixed decoder length. Short clips stop early instead of running all 124 steps.
         */
        internal fun maxNewTokens(windowSamples: Int): Int {
            val seconds = windowSamples.toDouble() / WhisperFeatureExtractor.SAMPLE_RATE
            return min(MAX_LEN - PROMPT_LENGTH, 8 + ceil(seconds * TOKENS_PER_SECOND).toInt())
        }

        fun load(context: Context, modelFile: File): WhisperEngine {
            val tokenizerJson = context.assets.open("whisper/tokenizer.json").bufferedReader().use { it.readText() }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_THREADS)
            val options = Interpreter.Options()
                .setNumThreads(threads)
                .setUseXNNPACK(true)
            val interpreter = Interpreter(modelFile, options)
            val signatures = interpreter.getSignatureKeys().toSet()
            require(signatures.containsAll(setOf("encode", "decode"))) {
                "El modelo no es un Whisper válido (firmas: $signatures)"
            }
            return WhisperEngine(interpreter, WhisperTokenizer(tokenizerJson))
        }
    }
}
