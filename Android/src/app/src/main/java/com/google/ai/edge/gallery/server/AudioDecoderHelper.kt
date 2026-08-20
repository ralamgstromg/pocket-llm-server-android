package com.google.ai.edge.gallery.server

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

private const val TAG = "AudioDecoderHelper"
private const val TARGET_SAMPLE_RATE = 16000

object AudioDecoderHelper {

    /**
     * Decodes any audio byte array (MP3, M4A, AAC, OGG, FLAC, WAV, AMR) into a 16-bit 16kHz Mono PCM WAV byte array.
     * Returns original bytes if already 16kHz mono WAV, or decoded PCM WAV bytes, or null on failure.
     */
    fun decodeToMonoPcmWav(context: Context, audioBytes: ByteArray): ByteArray {
        if (audioBytes.isEmpty()) return audioBytes

        // Check if already a 16kHz mono 16-bit WAV file
        if (is16kHzMonoWav(audioBytes)) {
            Log.d(TAG, "Audio is already 16kHz Mono WAV. No conversion needed.")
            return audioBytes
        }

        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("temp_audio_", ".tmp", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (trackMime != null && trackMime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    mime = trackMime
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null || mime == null) {
                Log.w(TAG, "No audio track found via MediaExtractor. Returning raw bytes.")
                extractor.release()
                return audioBytes
            }

            extractor.selectTrack(audioTrackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOutputStream = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var isEOS = false

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val timeoutUs = 10000L

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(chunk, 0, info.size)
                        pcmOutputStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val rawPcmBytes = pcmOutputStream.toByteArray()
            if (rawPcmBytes.isEmpty()) {
                Log.w(TAG, "Decoded PCM stream was empty. Returning original audio bytes.")
                return audioBytes
            }

            // Convert to 16-bit ShortArray
            val shortBuffer = ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var pcmSamples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(pcmSamples)

            // Convert Stereo to Mono if needed
            if (channels > 1) {
                val monoSamples = ShortArray(pcmSamples.size / channels)
                for (i in monoSamples.indices) {
                    var sum = 0
                    for (c in 0 until channels) {
                        sum += pcmSamples[i * channels + c].toInt()
                    }
                    monoSamples[i] = (sum / channels).toShort()
                }
                pcmSamples = monoSamples
            }

            // Resample to 16000 Hz if needed
            if (sampleRate != TARGET_SAMPLE_RATE) {
                pcmSamples = resampleMono(pcmSamples, sampleRate, TARGET_SAMPLE_RATE)
                sampleRate = TARGET_SAMPLE_RATE
            }

            // Write 44-byte WAV header + PCM 16-bit mono 16kHz
            val finalWavBytes = createWavFileBytes(pcmSamples, TARGET_SAMPLE_RATE)
            Log.d(TAG, "Successfully decoded audio to 16kHz Mono WAV (${finalWavBytes.size} bytes).")
            return finalWavBytes

        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio via MediaCodec", e)
            return audioBytes
        } finally {
            tempFile?.delete()
        }
    }

    private fun is16kHzMonoWav(bytes: ByteArray): Boolean {
        if (bytes.size < 44) return false
        val riff = String(bytes, 0, 4)
        val wave = String(bytes, 8, 4)
        if (riff != "RIFF" || wave != "WAVE") return false

        val headerBuffer = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val channels = headerBuffer.getShort(22).toInt()
        val sampleRate = headerBuffer.getInt(24)
        return channels == 1 && sampleRate == TARGET_SAMPLE_RATE
    }

    private fun resampleMono(inputSamples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || inputSamples.isEmpty()) return inputSamples
        val ratio = toRate.toDouble() / fromRate
        val outputLength = (inputSamples.size * ratio).toInt()
        val resampled = ShortArray(outputLength)
        for (i in resampled.indices) {
            val position = i / ratio
            val index1 = floor(position).toInt()
            val index2 = (index1 + 1).coerceAtMost(inputSamples.size - 1)
            val fraction = position - index1

            val sample1 = inputSamples[index1].toDouble()
            val sample2 = inputSamples[index2].toDouble()
            resampled[i] = (sample1 * (1 - fraction) + sample2 * fraction).toInt().toShort()
        }
        return resampled
    }

    private fun createWavFileBytes(pcmSamples: ShortArray, sampleRate: Int): ByteArray {
        val pcmDataSize = pcmSamples.size * 2
        val totalSize = 44 + pcmDataSize
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalSize - 8)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
        buffer.putShort(1.toShort()) // NumChannels = 1
        buffer.putInt(sampleRate) // SampleRate
        buffer.putInt(sampleRate * 2) // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        buffer.putShort(2.toShort()) // BlockAlign = NumChannels * BitsPerSample/8
        buffer.putShort(16.toShort()) // BitsPerSample = 16

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(pcmDataSize)

        val shortBuffer = buffer.asShortBuffer()
        shortBuffer.put(pcmSamples)

        return buffer.array()
    }
}
