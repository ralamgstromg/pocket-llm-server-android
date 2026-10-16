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
    /** Model or native service that served the request, or null when the route does not use a model. */
    val model: String? = null,
)

/** Outcome of checking one response. [error] is null when the route behaves as documented. */
internal data class Verdict(val error: String?, val info: String = "OK")

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
        val fallbackChatModel = PocketNodeState.activeChatModel?.name
            ?: PocketNodeState.preferredChatModelName.ifBlank { null }

        listOf(
            testRoute("GET /", fallbackModel = null, send = { get("/") }) { reply ->
                checkHealth(reply.code, reply.body)
            },
            testRoute(
                "POST /v1/chat/completions",
                fallbackModel = fallbackChatModel,
                send = { postJson("/v1/chat/completions", CHAT_BODY) },
            ) { reply ->
                checkChat(reply.code, reply.body)
            },
            testRoute(
                "POST /v1/audio/transcriptions",
                fallbackModel = AndroidNativeSttEngine.ENGINE_MODEL_ID,
                send = { postAudio() },
            ) { reply ->
                checkTranscription(reply.code, reply.body, reply.timings)
            },
        )
    }

    internal fun checkHealth(code: Int, body: String): Verdict = when {
        code != 200 -> Verdict("HTTP $code: ${body.take(200)}")
        body.trim() != HEALTH_BODY -> Verdict("Cuerpo inesperado: ${body.take(200)}")
        else -> Verdict(null)
    }

    internal fun checkChat(code: Int, body: String): Verdict {
        val content = runCatching {
            Json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull()
        return when {
            code != 200 -> Verdict("HTTP $code: ${body.take(200)}")
            content == null -> Verdict("Respuesta sin choices[0].message.content")
            content.contains(INFERENCE_ERROR_MARKER) -> Verdict(content.take(200))
            else -> Verdict(null, "Respuesta: ${content.take(200)}")
        }
    }

    internal fun checkTranscription(code: Int, body: String, timings: String?): Verdict {
        val text = runCatching {
            Json.parseToJsonElement(body).jsonObject["text"]!!.jsonPrimitive.content
        }.getOrNull()
        val timingInfo = timings?.let { " · tiempos: $it" }.orEmpty()
        return when {
            code != 200 -> Verdict("HTTP $code: ${body.take(200)}")
            text == null -> Verdict("Respuesta sin campo 'text'")
            text.contains(INFERENCE_ERROR_MARKER) -> Verdict(text.take(200))
            else -> Verdict(null, "Texto transcrito: ${text.ifEmpty { "(vacío, audio de silencio)" }.take(200)}$timingInfo")
        }
    }

    /** Extracts the serving model name from the HTTP header, JSON body (`model` field), or configured fallback. */
    internal fun resolveModelName(headerModel: String?, body: String, fallbackModel: String?): String? {
        headerModel?.takeIf { it.isNotBlank() }?.let { return it }
        val bodyModel = runCatching {
            Json.parseToJsonElement(body).jsonObject["model"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return bodyModel ?: fallbackModel?.takeIf { it.isNotBlank() }
    }

    private class HttpReply(val code: Int, val body: String, val timings: String?, val model: String?)

    private fun testRoute(
        route: String,
        fallbackModel: String?,
        send: () -> HttpReply,
        verify: (HttpReply) -> Verdict,
    ): ApiTestResult {
        val start = System.currentTimeMillis()
        val reply = try {
            send()
        } catch (e: IOException) {
            return ApiTestResult(
                route = route,
                ok = false,
                httpCode = null,
                millis = System.currentTimeMillis() - start,
                detail = "Sin respuesta: ${e.message}",
                model = fallbackModel?.takeIf { it.isNotBlank() },
            )
        }
        val verdict = verify(reply)
        val modelName = resolveModelName(reply.model, reply.body, fallbackModel)
        return ApiTestResult(
            route = route,
            ok = verdict.error == null,
            httpCode = reply.code,
            millis = System.currentTimeMillis() - start,
            detail = verdict.error ?: verdict.info,
            model = modelName,
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
            return HttpReply(code, text, connection.getHeaderField(TIMINGS_HEADER), connection.getHeaderField(MODEL_HEADER))
        } finally {
            connection.disconnect()
        }
    }

    /** One second of 16 kHz mono 16-bit PCM silence wrapped in a WAV header. */
    internal fun silentWav(): ByteArray {
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
