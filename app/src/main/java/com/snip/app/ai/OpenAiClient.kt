package com.snip.app.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Works for both OpenAI's Chat Completions API and OpenRouter (OpenAI-compatible). */
class OpenAiClient(private val baseUrl: String, private val providerLabel: String) : AiClient {

    override fun streamReply(apiKey: String, model: String, history: List<ChatTurn>): Flow<StreamEvent> =
        callbackFlow {
            val body = buildJsonObject {
                put("model", model)
                put("stream", true)
                putJsonArray("messages") {
                    history.forEach { turn ->
                        add(buildJsonObject {
                            put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
                            if (turn.images.isEmpty()) {
                                put("content", turn.text)
                            } else {
                                putJsonArray("content") {
                                    if (turn.text.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", turn.text)
                                        })
                                    }
                                    turn.images.forEach { image ->
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", "data:image/png;base64,${image.base64Png}")
                                            })
                                        })
                                    }
                                }
                            }
                        })
                    }
                }
            }.toString()

            val headers = buildMap {
                put("Authorization", "Bearer $apiKey")
                put("content-type", "application/json")
                if (baseUrl.contains("openrouter")) {
                    put("HTTP-Referer", "https://snip.app")
                    put("X-Title", "Snip")
                }
            }

            streamSse(
                url = "$baseUrl/chat/completions",
                headers = headers,
                bodyJson = body,
                onEvent = { data ->
                    if (data == "[DONE]") {
                        trySend(StreamEvent.Done)
                        return@streamSse
                    }
                    runCatching {
                        val json = Json.parseToJsonElement(data).jsonObject
                        val delta = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        val text = delta?.get("content")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) trySend(StreamEvent.TextDelta(text))
                    }
                },
                onFailure = { t, _ -> trySend(StreamEvent.Error(t.message ?: "$providerLabel network error")); close() },
                onClosed = { trySend(StreamEvent.Done); close() },
            )

            awaitClose { }
        }
}
