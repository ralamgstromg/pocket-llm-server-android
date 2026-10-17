package com.google.ai.edge.gallery.server

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApiOperationLogEntry(
    val timestamp: String,
    val method: String,
    val route: String,
    val statusCode: Int,
    val durationMs: Long,
    val model: String? = null,
    val detail: String = "",
) {
    fun formatLine(): String {
        val modelPart = model?.takeIf { it.isNotBlank() }?.let { " | model=$it" }.orEmpty()
        val detailPart = detail.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        return "[$timestamp] $method $route -> HTTP $statusCode (${durationMs}ms)$modelPart$detailPart"
    }
}

object PocketNodeLogger {
    internal const val MAX_ENTRIES = 500
    /** Lines are written to the log file in batches of this size, not one open/write/close per request. */
    internal const val FILE_BATCH_SIZE = 20
    /** The log file is trimmed to its newest half once it grows beyond this size. */
    internal const val MAX_FILE_BYTES = 256L * 1024
    private const val PREFS_NAME = "pocket_node_prefs"
    private const val KEY_LOGGING_ENABLED = "api_logging_enabled"
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "pocket_node_api.log"

    private val lock = Any()
    private val buffer = ArrayDeque<ApiOperationLogEntry>()
    private val pendingFileLines = ArrayList<String>()

    var isLoggingEnabled by mutableStateOf(true)
    var entries by mutableStateOf<List<ApiOperationLogEntry>>(emptyList())
        private set

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isLoggingEnabled = prefs.getBoolean(KEY_LOGGING_ENABLED, true)
    }

    fun setLoggingEnabled(context: Context?, enabled: Boolean) {
        isLoggingEnabled = enabled
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_LOGGING_ENABLED, enabled)
            ?.apply()
    }

    fun log(
        method: String,
        route: String,
        statusCode: Int,
        durationMs: Long,
        model: String? = null,
        detail: String = "",
        context: Context? = null,
        timestamp: String = nowFormatted(),
    ) {
        if (!isLoggingEnabled) return
        val sanitizedDetail = detail.replace('\n', ' ').replace('\r', ' ').trim().take(300)
        val entry = ApiOperationLogEntry(
            timestamp = timestamp,
            method = method,
            route = route,
            statusCode = statusCode,
            durationMs = durationMs,
            model = model?.takeIf { it.isNotBlank() },
            detail = sanitizedDetail,
        )
        val snapshot = synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            buffer.toList()
        }
        entries = snapshot

        if (context != null) {
            val batch = synchronized(lock) {
                pendingFileLines += entry.formatLine()
                if (pendingFileLines.size >= FILE_BATCH_SIZE) drainPendingLocked() else null
            }
            if (batch != null) writeLines(context, batch)
        }
    }

    /**
     * Writes any batched lines to the log file. Called on hibernation and server stop so the file is
     * current when the server goes idle. Lines still batched are lost only if the process is killed.
     */
    fun flush(context: Context) {
        val batch = synchronized(lock) { drainPendingLocked() } ?: return
        writeLines(context, batch)
    }

    private fun drainPendingLocked(): List<String>? {
        if (pendingFileLines.isEmpty()) return null
        val batch = pendingFileLines.toList()
        pendingFileLines.clear()
        return batch
    }

    private fun writeLines(context: Context, lines: List<String>) {
        runCatching {
            val file = getLogFile(context)
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val existing = file.readLines(Charsets.UTF_8)
                file.writeText((existing.takeLast(existing.size / 2) + lines).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            } else {
                file.appendText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    fun formatExportText(logEntries: List<ApiOperationLogEntry> = entries): String {
        if (logEntries.isEmpty()) {
            return "# Pocket Node API Operation Log\n(Sin operaciones registradas)\n"
        }
        return buildString {
            appendLine("# Pocket Node API Operation Log")
            appendLine("# Total operaciones: ${logEntries.size}")
            appendLine()
            logEntries.forEach { entry ->
                appendLine(entry.formatLine())
            }
        }
    }

    fun clear(context: Context? = null) {
        synchronized(lock) {
            buffer.clear()
            pendingFileLines.clear()
        }
        entries = emptyList()
        if (context != null) {
            runCatching {
                val file = getLogFile(context)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    fun exportToUri(context: Context, targetUri: Uri): Result<Unit> = runCatching {
        val content = formatExportText()
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: error("No se pudo abrir el destino para escritura.")
    }

    fun shareLogs(context: Context): Result<Unit> = runCatching {
        val content = formatExportText()
        val file = getLogFile(context)
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Pocket Node - Log de Operaciones API")
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("pocket_node_api.log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Compartir log de operaciones API").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun defaultExportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "pocket-node-api-$stamp.log"
    }

    private fun getLogFile(context: Context): File {
        val dir = File(context.cacheDir, LOG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, LOG_FILE_NAME)
    }

    private fun nowFormatted(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
