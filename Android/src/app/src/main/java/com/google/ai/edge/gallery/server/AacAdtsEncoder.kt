package com.google.ai.edge.gallery.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Encodes 16-bit mono PCM to AAC-LC using the platform [MediaCodec] encoder, framed as ADTS so the
 * output is a plain `.aac` stream that players and ffmpeg read without a container. Used for
 * `response_format=aac` on speech; roughly 8x smaller than WAV for speech at [BIT_RATE_BPS].
 */
internal object AacAdtsEncoder {

    private const val BIT_RATE_BPS = 48_000
    private const val TIMEOUT_US = 10_000L
    private const val DEADLINE_MS = 60_000L
    private const val BYTES_PER_SAMPLE = 2

    /** ADTS sampling-frequency index table (ISO/IEC 14496-3). */
    private val SAMPLE_RATE_INDEX = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Builds the 7-byte ADTS header for one AAC-LC frame of [frameLength] payload bytes. */
    internal fun adtsHeader(frameLength: Int, sampleRateHz: Int, channels: Int): ByteArray {
        val freqIndex = SAMPLE_RATE_INDEX.indexOf(sampleRateHz)
        require(freqIndex >= 0) { "Frecuencia no soportada por ADTS: $sampleRateHz" }
        val fullLength = frameLength + 7
        val aacLcProfile = 1 // object type 2 (AAC-LC) minus one
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // syncword, MPEG-4, layer 0, no CRC
            ((aacLcProfile shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (fullLength shr 11)).toByte(),
            ((fullLength shr 3) and 0xFF).toByte(),
            (((fullLength and 7) shl 5) or 0x1F).toByte(), // buffer fullness: variable bitrate
            0xFC.toByte(), // one raw data block per frame
        )
    }

    /** Blocking encode. Call from a background dispatcher. */
    fun encodeAdts(pcm: ByteArray, sampleRateHz: Int): ByteArray {
        require(SAMPLE_RATE_INDEX.contains(sampleRateHz)) { "Frecuencia no soportada por ADTS: $sampleRateHz" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputOffset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(System.currentTimeMillis() < deadline) { "Tiempo agotado codificando AAC." }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val chunk = minOf(buffer.capacity(), pcm.size - inputOffset)
                        val presentationUs = (inputOffset / BYTES_PER_SAMPLE) * 1_000_000L / sampleRateHz
                        if (chunk <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.clear()
                            buffer.put(pcm, inputOffset, chunk)
                            codec.queueInputBuffer(inIndex, 0, chunk, presentationUs, 0)
                            inputOffset += chunk
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frame = ByteArray(info.size).also { buffer.get(it) }
                        out.write(adtsHeader(frame.size, sampleRateHz, 1))
                        out.write(frame)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return out.toByteArray()
    }
}
