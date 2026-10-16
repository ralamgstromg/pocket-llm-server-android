package com.google.ai.edge.gallery.server

import java.util.Locale

/** One transcribed span of audio, in seconds from the start of the uploaded file. */
data class SttSegment(val start: Double, val end: Double, val text: String)

/** Renders [segments] as SubRip (`response_format=srt`). */
internal fun formatSrt(segments: List<SttSegment>): String =
    segments.withIndex().joinToString("") { (index, segment) ->
        "${index + 1}\n" +
            "${formatTimestamp(segment.start, ',')} --> ${formatTimestamp(segment.end, ',')}\n" +
            "${segment.text}\n\n"
    }

/** Renders [segments] as WebVTT (`response_format=vtt`). */
internal fun formatVtt(segments: List<SttSegment>): String =
    "WEBVTT\n\n" + segments.joinToString("") { segment ->
        "${formatTimestamp(segment.start, '.')} --> ${formatTimestamp(segment.end, '.')}\n" +
            "${segment.text}\n\n"
    }

/** `HH:MM:SS<sep>mmm`, where [msSeparator] is `,` for SRT and `.` for WebVTT. */
internal fun formatTimestamp(seconds: Double, msSeparator: Char): String {
    val totalMs = Math.round(seconds.coerceAtLeast(0.0) * 1000.0)
    val hours = totalMs / 3_600_000
    val minutes = totalMs / 60_000 % 60
    val secs = totalMs / 1_000 % 60
    val millis = totalMs % 1_000
    return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, minutes, secs, msSeparator, millis)
}
