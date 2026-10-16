package com.google.ai.edge.gallery.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ApiTestResult(
    val route: String,
    val ok: Boolean,
    val httpCode: Int?,
    val millis: Long,
    val detail: String,
)

/** Calls each route of the local Pocket Node server and checks that it responds as documented. */
object PocketNodeApiTester {
    private const val CONNECT_TIMEOUT_MS = 3_000
    // Inference on-device can be slow, so reads wait much longer than connects.
    private const val READ_TIMEOUT_MS = 120_000
    private const val BASE_URL = "http://127.0.0.1:${PocketNodeServer.PORT}"
    private const val HEALTH_BODY = "Pocket Node AI is running!"
    // Marker the server appends to the output when inference fails (still returns HTTP 200).
    private const val INFERENCE_ERROR_MARKER = "[Error:"
    private const val CHAT_BODY = """{"messages":[{"role":"user","content":"Responde solo: ok"}]}"""

    suspend fun runAll(): List<ApiTestResult> = withContext(Dispatchers.IO) {
        listOf(
            testRoute("GET /", { get("/") }) { reply ->
                when {
                    reply.code != 200 -> "HTTP ${reply.code}: ${reply.body.take(200)}"
                    reply.body.trim() != HEALTH_BODY -> "Cuerpo inesperado: ${reply.body.take(200)}"
                    else -> null
                }
            },
            testRoute("POST /v1/chat/completions", { postJson("/v1/chat/completions", CHAT_BODY) }) { reply ->
                val content = runCatching {
                    Json.parseToJsonElement(reply.body).jsonObject["choices"]!!.jsonArray[0]
                        .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                }.getOrNull()
                when {
                    reply.code != 200 -> "HTTP ${reply.code}: ${reply.body.take(200)}"
                    content == null -> "Respuesta sin choices[0].message.content"
                    content.contains(INFERENCE_ERROR_MARKER) -> content.take(200)
                    else -> "Respuesta: ${content.take(200)}"
                }
            },
            testRoute("POST /v1/audio/transcriptions", { postAudio() }) { reply ->
                val text = runCatching {
                    Json.parseToJsonElement(reply.body).jsonObject["text"]!!.jsonPrimitive.content
                }.getOrNull()
                when {
                    reply.code != 200 -> "HTTP ${reply.code}: ${reply.body.take(200)}"
                    text == null -> "Respuesta sin campo 'text'"
                    text.contains(INFERENCE_ERROR_MARKER) -> text.take(200)
                    else -> "Texto transcrito: ${text.ifEmpty { "(vacío, audio de silencio)" }.take(200)}"
                }
            },
        )
    }

    private class HttpReply(val code: Int, val body: String)

    private fun testRoute(
        route: String,
        send: () -> HttpReply,
        verify: (HttpReply) -> String?,
    ): ApiTestResult {
        val start = System.currentTimeMillis()
        val reply = try {
            send()
        } catch (e: IOException) {
            return ApiTestResult(route, false, null, System.currentTimeMillis() - start, "Sin respuesta: ${e.message}")
        }
        val error = verify(reply)
        return ApiTestResult(
            route = route,
            ok = error == null,
            httpCode = reply.code,
            millis = System.currentTimeMillis() - start,
            detail = error ?: "OK",
        )
    }

    private fun get(path: String): HttpReply = send(path, "GET", null, null)

    private fun postJson(path: String, json: String): HttpReply =
        send(path, "POST", "application/json", json.toByteArray(Charsets.UTF_8))

    private fun postAudio(): HttpReply {
        val boundary = "PocketNodeApiTest${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"silence.wav\"\r\n".toByteArray())
            write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            write(silentWav())
            write("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nes\r\n".toByteArray())
            write("--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return send("/v1/audio/transcriptions", "POST", "multipart/form-data; boundary=$boundary", body)
    }

    private fun send(path: String, method: String, contentType: String?, body: ByteArray?): HttpReply {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpReply(code, text)
        } finally {
            connection.disconnect()
        }
    }

    /** One second of 16 kHz mono 16-bit PCM silence wrapped in a WAV header. */
    private fun silentWav(): ByteArray {
        val sampleRate = 16_000
        val dataSize = sampleRate * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray())
            putInt(dataSize)
            put(ByteArray(dataSize))
        }.array()
    }
}
