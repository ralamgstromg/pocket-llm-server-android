package com.google.ai.edge.gallery.server.whisper

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 128-bin log-mel spectrogram for Whisper, matching `transformers.WhisperFeatureExtractor`:
 * 16 kHz, 30 s fixed window, n_fft 400, hop 160, periodic Hann window, reflect padding, Slaney mel scale.
 */
object WhisperFeatureExtractor {
    const val SAMPLE_RATE = 16000
    const val N_SAMPLES = 480000
    const val N_FRAMES = 3000
    const val N_MELS = 128

    private const val N_FFT = 400
    private const val HOP = 160
    private const val N_BINS = N_FFT / 2 + 1
    private const val MIN_LOG_HZ = 1000.0
    private const val MIN_LOG_MEL = 15.0
    // Declared before melFilters: object properties initialize in order.
    private val LOG_STEP = 27.0 / ln(6.4)

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
    private val cosTable = Array(N_BINS) { k -> DoubleArray(N_FFT) { n -> cos(2 * PI * k * n / N_FFT) } }
    private val sinTable = Array(N_BINS) { k -> DoubleArray(N_FFT) { n -> sin(2 * PI * k * n / N_FFT) } }
    private val melFilters: Array<DoubleArray> = buildMelFilters()

    /**
     * Returns the log-mel features as a flat row-major array of shape [N_MELS, N_FRAMES].
     * Input longer than 30 s is truncated; shorter input is zero-padded.
     */
    fun extract(samples: FloatArray): FloatArray {
        val signal = DoubleArray(N_SAMPLES)
        for (i in 0 until min(samples.size, N_SAMPLES)) signal[i] = samples[i].toDouble()

        val half = N_FFT / 2
        val mel = DoubleArray(N_MELS * N_FRAMES)

        // Frames are independent. Each worker writes only its own column of `mel`.
        IntStream.range(0, N_FRAMES).parallel().forEach { f ->
            val frame = DoubleArray(N_FFT)
            val power = DoubleArray(N_BINS)
            for (n in 0 until N_FFT) {
                frame[n] = window[n] * signal[reflect(f * HOP + n - half)]
            }
            for (k in 0 until N_BINS) {
                var re = 0.0
                var im = 0.0
                val c = cosTable[k]
                val s = sinTable[k]
                for (n in 0 until N_FFT) {
                    re += frame[n] * c[n]
                    im -= frame[n] * s[n]
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val filter = melFilters[m]
                var sum = 0.0
                for (k in 0 until N_BINS) sum += filter[k] * power[k]
                mel[m * N_FRAMES + f] = sum
            }
        }

        var maxLog = Double.NEGATIVE_INFINITY
        for (i in mel.indices) {
            mel[i] = log10(max(mel[i], 1e-10))
            if (mel[i] > maxLog) maxLog = mel[i]
        }
        return FloatArray(mel.size) { i ->
            (max(mel[i], maxLog - 8.0) + 4.0).div(4.0).toFloat()
        }
    }

    /** Reflects an index into [0, N_SAMPLES) the way numpy's "reflect" padding does. */
    private fun reflect(index: Int): Int {
        var i = index
        if (i < 0) i = -i
        if (i >= N_SAMPLES) i = 2 * (N_SAMPLES - 1) - i
        return i
    }

    private fun buildMelFilters(): Array<DoubleArray> {
        val fMin = 0.0
        val fMax = 8000.0
        val allFreqs = DoubleArray(N_BINS) { it * (SAMPLE_RATE / 2.0) / (N_BINS - 1) }
        val melMin = hzToSlaneyMel(fMin)
        val melMax = hzToSlaneyMel(fMax)
        val melPoints = DoubleArray(N_MELS + 2) { melMin + it * (melMax - melMin) / (N_MELS + 1) }
        val fPoints = DoubleArray(melPoints.size) { slaneyMelToHz(melPoints[it]) }
        val fDiff = DoubleArray(fPoints.size - 1) { fPoints[it + 1] - fPoints[it] }

        return Array(N_MELS) { m ->
            DoubleArray(N_BINS) { k ->
                val down = -(fPoints[m] - allFreqs[k]) / fDiff[m]
                val up = (fPoints[m + 2] - allFreqs[k]) / fDiff[m + 1]
                // Slaney area normalization.
                max(0.0, min(down, up)) * 2.0 / (fPoints[m + 2] - fPoints[m])
            }
        }
    }

    private fun hzToSlaneyMel(hz: Double): Double =
        if (hz < MIN_LOG_HZ) 3.0 * hz / 200.0
        else MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) * LOG_STEP

    private fun slaneyMelToHz(mel: Double): Double =
        if (mel < MIN_LOG_MEL) 200.0 * mel / 3.0
        else MIN_LOG_HZ * exp((mel - MIN_LOG_MEL) / LOG_STEP)
}
