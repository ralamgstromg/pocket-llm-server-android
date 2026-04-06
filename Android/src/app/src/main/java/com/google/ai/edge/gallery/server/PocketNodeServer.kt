package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.EmbeddedServer
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Serializable
data class ChatRequest(val model: String = "", val messages: List<Message> = emptyList())

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatResponse(val id: String, val choices: List<Choice>)

@Serializable
data class Choice(val message: Message)

class PocketNodeServer {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        PocketNodeState.isServerRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(CIO, port = 8080) {
                install(CORS) {
                    anyHost() // Allow connections from n8n or any local network client
                    allowHeader(io.ktor.http.HttpHeaders.ContentType)
                }
                install(ContentNegotiation) {
                    json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
                }

                routing {
                    get("/") {
                        call.respondText("Pocket Node AI is running!")
                    }

                    post("/v1/chat/completions") {
                        val req = call.receive<ChatRequest>()
                        val prompt = req.messages.lastOrNull()?.content ?: ""
                        
                        val model = PocketNodeState.activeModel
                        if (model == null || model.instance == null) {
                            call.respondText("Error: No active model initialized. Please open a model in the Gallery App first.", status = HttpStatusCode.ServiceUnavailable)
                            return@post
                        }
                        
                        var fullResponse = ""
                        suspendCoroutine { continuation ->
                            LlmChatModelHelper.runInference(
                                model = model,
                                input = prompt,
                                resultListener = { partialResult, done, _ ->
                                    fullResponse += partialResult
                                    if (done) {
                                        continuation.resume(Unit)
                                    }
                                },
                                cleanUpListener = {},
                                onError = { errorMsg ->
                                    fullResponse += "\\n[Error: $errorMsg]"
                                    continuation.resume(Unit)
                                }
                            )
                        }

                        val reply = ChatResponse(
                            id = "chatcmpl-${System.currentTimeMillis()}",
                            choices = listOf(Choice(Message("assistant", fullResponse)))
                        )
                        call.respond(reply)
                    }
                }
            }.start(wait = false)
        }
    }

    fun stop() {
        PocketNodeState.isServerRunning = false
        server?.stop(1000, 2000, TimeUnit.MILLISECONDS)
        server = null
    }
}
