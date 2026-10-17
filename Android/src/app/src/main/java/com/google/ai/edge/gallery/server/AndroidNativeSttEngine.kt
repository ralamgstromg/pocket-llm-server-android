package com.google.ai.edge.gallery.server

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AndroidNativeSttEngine"

/** Diagnostic timing breakdown for the Android Native STT service. */
data class NativeSttTimings(
    val decodeMs: Long,
    val recognizeMs: Long,
    val segments: Int,
    val audioSeconds: Float,
    val engineMode: String,
) {
    fun toHeaderValue(): String =
        "decode=${decodeMs}ms recognize=${recognizeMs}ms segments=$segments audio=${"%.1f".format(Locale.US, audioSeconds)}s mode=$engineMode"
}

data class NativeSttOutput(
    val text: String,
    val segments: List<SttSegment>,
    val modelName: String,
    val timings: NativeSttTimings,
)

/** A recognizer error with its [SpeechRecognizer] code, so callers can tell retryable failures apart. */
class SttRecognitionException(val errorCode: Int, message: String) : IllegalStateException(message)

/** Thrown when too many transcriptions are already queued behind the recognizer. Maps to HTTP 429. */
class SttBusyException : IllegalStateException("Servicio STT ocupado: demasiadas transcripciones en cola. Reintente en unos segundos.")

/**
 * Speech-to-Text engine powered 100% by the phone's built-in Android SpeechRecognition service
 * ([SpeechRecognizer]) without loading heavy local Whisper/LLM models:
 * - Decodes uploaded audio (`mp3`, `m4a`, `wav`, `ogg`, `flac`, `aac`) into 16 kHz 16-bit mono PCM.
 * - Streams the PCM audio directly into `SpeechRecognizer` via [RecognizerIntent.EXTRA_AUDIO_SOURCE]
 *   ([ParcelFileDescriptor.createPipe]), bypassing the physical microphone so it works reliably
 *   even when the phone screen is off and locked.
 * - Acquires [PocketNodeInference.withInferenceWakeLock] during transcription so the CPU does not
 *   suspend while the screen is off.
 */
object AndroidNativeSttEngine {

    const val ENGINE_MODEL_ID = "Android-Native-STT"
    const val SAMPLE_RATE_HZ = 16_000
    const val BYTES_PER_SAMPLE = 2 // 16-bit PCM mono
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE // 32,000 bytes/sec

    /** Maximum segment duration (25 seconds) before splitting on low-energy boundaries. */
    internal const val MAX_SEGMENT_SECONDS = 25
    internal const val MAX_SEGMENT_BYTES = MAX_SEGMENT_SECONDS * BYTES_PER_SECOND

    /** The recognizer handles one utterance at a time; extra requests wait on [sttMutex] holding their upload in memory. */
    internal const val MAX_PENDING_REQUESTS = 4
    private val pendingRequests = AtomicInteger(0)
    private val sttMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))

    /** Maps ISO-639-1 codes (`es`, `en`, `pt`, `fr`) or BCP-47 tags to full Android locale tags. */
    internal fun normalizeLocaleTag(language: String?): String {
        val raw = language?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("auto", ignoreCase = true)) {
            return "es-ES"
        }
        val normalized = raw.replace('_', '-')
        if (normalized.contains('-')) {
            return normalized
        }
        return when (normalized.lowercase(Locale.ROOT)) {
            "es", "spa", "spanish", "español" -> "es-ES"
            "en", "eng", "english" -> "en-US"
            "pt", "por", "portuguese" -> "pt-BR"
            "fr", "fra", "french" -> "fr-FR"
            "de", "deu", "german" -> "de-DE"
            "it", "ita", "italian" -> "it-IT"
            else -> normalized
        }
    }

    /**
     * Splits a 16 kHz 16-bit mono PCM byte array into segments of at most [maxSegmentBytes],
     * choosing low-energy split points within the last 3 seconds of each window so words are not cut mid-syllable.
     */
    internal fun splitPcmIntoSegments(
        pcmBytes: ByteArray,
        maxSegmentBytes: Int = MAX_SEGMENT_BYTES,
    ): List<ByteArray> {
        val alignedSize = pcmBytes.size - (pcmBytes.size and 1)
        if (alignedSize <= 0) return emptyList()
        if (alignedSize <= maxSegmentBytes) {
            return listOf(if (alignedSize == pcmBytes.size) pcmBytes else pcmBytes.copyOf(alignedSize))
        }

        val segments = mutableListOf<ByteArray>()
        var offset = 0
        val searchWindowBytes = minOf(3 * BYTES_PER_SECOND, maxSegmentBytes / 4).let { it - (it and 1) }

        while (offset < alignedSize) {
            val remaining = alignedSize - offset
            if (remaining <= maxSegmentBytes) {
                segments += pcmBytes.copyOfRange(offset, alignedSize)
                break
            }
            val nominalEnd = offset + maxSegmentBytes
            val splitPoint = findQuietSplitPoint(
                pcmBytes = pcmBytes,
                windowStart = (nominalEnd - searchWindowBytes).coerceAtLeast(offset + BYTES_PER_SECOND),
                windowEnd = nominalEnd,
            )
            segments += pcmBytes.copyOfRange(offset, splitPoint)
            offset = splitPoint
        }
        return segments
    }

    private fun findQuietSplitPoint(pcmBytes: ByteArray, windowStart: Int, windowEnd: Int): Int {
        val frameBytes = (SAMPLE_RATE_HZ / 20) * BYTES_PER_SAMPLE // 50 ms frame = 1600 bytes
        if (windowEnd - windowStart < frameBytes) return windowEnd

        var bestOffset = windowEnd
        var lowestEnergy = Long.MAX_VALUE
        var pos = windowStart - (windowStart and 1)

        while (pos + frameBytes <= windowEnd) {
            var energy = 0L
            val buf = ByteBuffer.wrap(pcmBytes, pos, frameBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (buf.hasRemaining()) {
                energy += abs(buf.get().toInt())
            }
            if (energy < lowestEnergy) {
                lowestEnergy = energy
                bestOffset = pos + frameBytes / 2
            }
            pos += frameBytes / 2
        }
        return bestOffset - (bestOffset and 1)
    }

    /** Returns a human-readable description of the phone's native STT availability. */
    fun statusSummary(context: Context): String {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val systemAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return when {
            onDevice -> "Servicio STT Nativo del Móvil (On-Device + Sistema activo)"
            systemAvailable -> "Servicio STT Nativo del Móvil (Motor del Sistema activo)"
            else -> "Servicio STT del Sistema (verifica tener Google Speech Services habilitado)"
        }
    }

    /**
     * Transcribes any supported audio payload (`mp3`, `m4a`, `wav`, `ogg`, `flac`, `aac`) using the
     * phone's native [SpeechRecognizer] service. Safe to invoke while the screen is off.
     */
    suspend fun transcribe(
        context: Context,
        rawAudioBytes: ByteArray,
        language: String? = PocketNodeInference.DEFAULT_LANGUAGE,
        preferOffline: Boolean = PocketNodeState.preferOfflineNativeStt,
    ): Result<NativeSttOutput> {
        if (pendingRequests.incrementAndGet() > MAX_PENDING_REQUESTS) {
            pendingRequests.decrementAndGet()
            return Result.failure(SttBusyException())
        }
        try {
            return sttMutex.withLock {
                transcribeExclusive(context, rawAudioBytes, language, preferOffline)
            }
        } finally {
            pendingRequests.decrementAndGet()
        }
    }

    private suspend fun transcribeExclusive(
        context: Context,
        rawAudioBytes: ByteArray,
        language: String?,
        preferOffline: Boolean,
    ): Result<NativeSttOutput> {
        val appContext = context.applicationContext
        PocketNodeInference.touchActivity(appContext)
        return PocketNodeInference.withInferenceWakeLock(appContext) {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@withInferenceWakeLock Result.failure(
                    IllegalStateException("Falta el permiso de micrófono (RECORD_AUDIO) en la app. Conceda el permiso y reinicie el servidor.")
                )
            }
            val decodeStartMs = System.currentTimeMillis()
            val pcmBytes = withContext(Dispatchers.Default) {
                AudioDecoderHelper.decodeToMono16kPcmBytes(appContext, rawAudioBytes)
            } ?: return@withInferenceWakeLock Result.failure(
                IllegalArgumentException("Error: audio no decodificable a PCM 16 kHz mono.")
            )
            val decodeMs = System.currentTimeMillis() - decodeStartMs
            val audioSeconds = pcmBytes.size.toFloat() / BYTES_PER_SECOND.toFloat()

            // Fast-path for pure silence (e.g. self-test route or empty voice note):
            // returns immediately (< 2 ms) without binding SpeechRecognizer.
            if (AudioDecoderHelper.isPcmSilence(pcmBytes)) {
                val mode = "silence"
                return@withInferenceWakeLock Result.success(
                    NativeSttOutput(
                        text = "",
                        segments = emptyList(),
                        modelName = ENGINE_MODEL_ID,
                        timings = NativeSttTimings(
                            decodeMs = decodeMs,
                            recognizeMs = 0L,
                            segments = 0,
                            audioSeconds = audioSeconds,
                            engineMode = mode,
                        ),
                    )
                )
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return@withInferenceWakeLock Result.failure(
                    IllegalStateException("El modo STT por pipe de audio requiere Android 13 (API 33) o superior.")
                )
            }

            val localeTag = normalizeLocaleTag(language)
            val segments = splitPcmIntoSegments(pcmBytes)
            val recognizeStartMs = System.currentTimeMillis()
            val transcribedSegments = mutableListOf<SttSegment>()
            var activeMode = if (preferOffline) "native-ondevice" else "native-system"
            var offsetBytes = 0

            for (segment in segments) {
                val startSec = offsetBytes.toDouble() / BYTES_PER_SECOND
                offsetBytes += segment.size
                val endSec = offsetBytes.toDouble() / BYTES_PER_SECOND
                val segResult = recognizeSegmentWithFallback(
                    context = appContext,
                    pcmSegment = segment,
                    localeTag = localeTag,
                    preferOffline = preferOffline,
                )
                segResult.onSuccess { (segText, usedMode) ->
                    activeMode = usedMode
                    if (segText.isNotBlank()) {
                        transcribedSegments += SttSegment(start = startSec, end = endSec, text = segText.trim())
                    }
                }.onFailure { err ->
                    return@withInferenceWakeLock Result.failure(err)
                }
            }

            val recognizeMs = System.currentTimeMillis() - recognizeStartMs
            Result.success(
                NativeSttOutput(
                    text = transcribedSegments.joinToString(" ") { it.text },
                    segments = transcribedSegments,
                    modelName = ENGINE_MODEL_ID,
                    timings = NativeSttTimings(
                        decodeMs = decodeMs,
                        recognizeMs = recognizeMs,
                        segments = segments.size,
                        audioSeconds = audioSeconds,
                        engineMode = activeMode,
                    ),
                )
            )
        }
    }

    private suspend fun recognizeSegmentWithFallback(
        context: Context,
        pcmSegment: ByteArray,
        localeTag: String,
        preferOffline: Boolean,
    ): Result<Pair<String, String>> {
        val onDeviceSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        var offlinePackMissing = false

        if (preferOffline && onDeviceSupported) {
            val onDeviceAttempt = runSingleRecognition(
                context = context,
                pcmSegment = pcmSegment,
                localeTag = localeTag,
                useOnDeviceRecognizer = true,
                requestOfflineExtra = true,
            )
            if (onDeviceAttempt.isSuccess) {
                return onDeviceAttempt.map { it to "native-ondevice" }
            }
            Log.w(TAG, "On-device STT failed (${onDeviceAttempt.exceptionOrNull()?.message}); falling back to system SpeechRecognizer.")
            // A missing offline language pack also breaks the offline-preferring system attempt below,
            // but the standard system recognizer can still work online, so it is always tried.
            offlinePackMissing = onDeviceAttempt.exceptionOrNull()?.isLanguageError() == true
        }

        // System SpeechRecognizer (first with EXTRA_PREFER_OFFLINE if requested, then standard system mode)
        if (preferOffline && !offlinePackMissing) {
            val offlineSystemAttempt = runSingleRecognition(
                context = context,
                pcmSegment = pcmSegment,
                localeTag = localeTag,
                useOnDeviceRecognizer = false,
                requestOfflineExtra = true,
            )
            if (offlineSystemAttempt.isSuccess) {
                return offlineSystemAttempt.map { it to "native-offline" }
            }
        }

        return runSingleRecognition(
            context = context,
            pcmSegment = pcmSegment,
            localeTag = localeTag,
            useOnDeviceRecognizer = false,
            requestOfflineExtra = false,
        ).map { it to "native-system" }
    }

    private suspend fun runSingleRecognition(
        context: Context,
        pcmSegment: ByteArray,
        localeTag: String,
        useOnDeviceRecognizer: Boolean,
        requestOfflineExtra: Boolean,
    ): Result<String> {
        val segmentDurationMs = ((pcmSegment.size.toLong() * 1000L) / BYTES_PER_SECOND).coerceAtLeast(1000L)
        val timeoutMs = (segmentDurationMs + 20_000L).coerceIn(15_000L, 60_000L)

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<String>> { continuation ->
                val completed = AtomicBoolean(false)
                val pipe = try {
                    ParcelFileDescriptor.createPipe()
                } catch (e: Exception) {
                    if (completed.compareAndSet(false, true)) {
                        continuation.resume(Result.failure(e))
                    }
                    return@suspendCancellableCoroutine
                }
                val readPfd = pipe[0]
                val writePfd = pipe[1]

                // Stream 16kHz 16-bit mono PCM into the write-end of the pipe on an IO thread.
                val writerJob = ioScope.launch {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writePfd).use { out ->
                            val chunkBytes = BYTES_PER_SECOND / 10 // 100 ms chunks (3200 bytes)
                            var offset = 0
                            while (offset < pcmSegment.size) {
                                val len = minOf(chunkBytes, pcmSegment.size - offset)
                                out.write(pcmSegment, offset, len)
                                out.flush()
                                offset += len
                                // Pace slightly so SpeechRecognizer's internal ring buffer never overflows on long clips
                                delay(15L)
                            }
                            // Append 350 ms of trailing silence so the recognizer's endpoint detector flushes the final word
                            out.write(ByteArray((BYTES_PER_SECOND * 35) / 100))
                            out.flush()
                        }
                    } catch (ignored: Exception) {
                        // Pipe closed early by recognizer after final result
                    } finally {
                        runCatching { writePfd.close() }
                    }
                }

                // SpeechRecognizer MUST be created, invoked, and destroyed on the Main Looper.
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    var recognizer: SpeechRecognizer? = null
                    var lastPartial = ""

                    fun finish(result: Result<String>) {
                        if (completed.compareAndSet(false, true)) {
                            writerJob.cancel()
                            runCatching { readPfd.close() }
                            runCatching { writePfd.close() }
                            runCatching {
                                recognizer?.cancel()
                                recognizer?.destroy()
                            }
                            recognizer = null
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        CoroutineScope(Dispatchers.Main.immediate).launch {
                            finish(Result.failure( IllegalStateException("Transcripción cancelada.") ))
                        }
                    }

                    try {
                        val created = if (useOnDeviceRecognizer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                        } else {
                            SpeechRecognizer.createSpeechRecognizer(context)
                        }
                        recognizer = created

                        created.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}

                            override fun onPartialResults(partialResults: Bundle?) {
                                val partials = partialResults
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    ?.trim()
                                if (!partials.isNullOrEmpty()) {
                                    lastPartial = partials
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val best = matches?.firstOrNull { it.isNotBlank() }?.trim()
                                    ?: lastPartial.trim()
                                finish(Result.success(best))
                            }

                            override fun onError(error: Int) {
                                // ERROR_NO_MATCH (7) or ERROR_SPEECH_TIMEOUT (6) when audio has no recognizable speech:
                                // return partial transcript if we captured any, or empty string instead of HTTP 500.
                                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                    finish(Result.success(lastPartial.trim()))
                                } else {
                                    val message = when (error) {
                                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                            "Idioma '$localeTag' no disponible en el reconocedor del móvil. Instale el paquete de voz sin conexión de ese idioma."
                                        else ->
                                            "Error en servicio STT nativo (${speechErrorName(error)}, código $error)"
                                    }
                                    finish(Result.failure(SttRecognitionException(error, message)))
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })

                        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            if (requestOfflineExtra) {
                                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPfd)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
                            }
                        }

                        created.startListening(recognizerIntent)
                    } catch (t: Throwable) {
                        finish(Result.failure(t))
                    }
                }
            }
        }

        return outcome ?: Result.failure(
            IllegalStateException("Tiempo de espera agotado en el servicio STT nativo del móvil (${timeoutMs} ms).")
        )
    }

    private fun Throwable.isLanguageError(): Boolean =
        this is SttRecognitionException &&
            (errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

    internal fun speechErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        else -> "ERROR_UNKNOWN_$error"
    }
}
