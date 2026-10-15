package com.google.ai.edge.gallery.server

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistent API request log: one JSON object per line in `files/logs/api-YYYY-MM-DD.jsonl`.
 *
 * Only metadata is written (endpoint, status, timing, model, sizes, finish reason, tokens, error),
 * never prompt or completion text or the API key. Files older than [RETENTION_DAYS] are deleted.
 * Writes happen on a single background thread so requests never wait on disk I/O.
 */
object ApiRequestLog {
  private const val TAG = "ApiRequestLog"
  private const val RETENTION_DAYS = 7
  private const val MAX_FILE_BYTES = 20L * 1024 * 1024

  private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "api-request-log").apply { isDaemon = true } }
  private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val isoFormat =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }

  @Volatile private var dir: File? = null

  fun init(context: Context) {
    dir = File(context.filesDir, "logs").apply { mkdirs() }
    writer.execute { pruneOldFiles() }
  }

  /** Log files, newest first. */
  fun files(): List<File> =
    dir?.listFiles { f -> f.name.startsWith("api-") && f.name.endsWith(".jsonl") }?.sortedByDescending { it.name }.orEmpty()

  fun file(day: String): File? = files().find { it.name == "api-$day.jsonl" }

  fun clear() = writer.execute { files().forEach { it.delete() } }

  fun record(entry: RequestLogEntry, details: Map<String, Any?>) {
    val target = dir ?: return
    val line =
      buildJsonObject {
          put("ts", isoFormat.format(Date(entry.timeMillis)))
          put("id", entry.id)
          put("method", entry.method)
          put("path", entry.path)
          put("status", entry.status)
          put("duration_ms", entry.durationMs)
          entry.model?.let { put("model", it) }
          entry.clientIp?.let { put("client_ip", it) }
          entry.error?.let { put("error", it.take(2000)) }
          details.forEach { (k, v) ->
            when (v) {
              null -> {}
              is Number -> put(k, v)
              is Boolean -> put(k, v)
              else -> put(k, v.toString().take(500))
            }
          }
        }
        .toString()
    writer.execute {
      try {
        val file = File(target, "api-${dayFormat.format(Date(entry.timeMillis))}.jsonl")
        if (file.length() < MAX_FILE_BYTES) file.appendText(line + "\n")
      } catch (e: Exception) {
        Log.w(TAG, "Could not write request log: ${e.message}")
      }
    }
  }

  private fun pruneOldFiles() {
    val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60 * 60 * 1000
    files().filter { it.lastModified() < cutoff }.forEach { it.delete() }
  }
}
