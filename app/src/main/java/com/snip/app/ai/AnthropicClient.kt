package com.snip.app.ai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AnthropicClient : AiClient {

    override fun streamReply(apiKey: String, model: String, history: List<ChatTurn>): Flow<StreamEvent> =
        callbackFlow {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", 4096)
                put("stream", true)
                putJsonArray("messages") {
                    history.forEach { turn ->
                        addJsonObject {
                            put("role", if (turn.role == ChatRole.USER) "user" else "assistant")
                            putJsonArray("content") {
                                turn.images.forEach { image ->
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", "image/png")
                                            put("data", image.base64Png)
                                        }
                                    }
                                }
                                if (turn.text.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", turn.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }.toString()

            streamSse(
                url = "https://api.anthropic.com/v1/messages",
                headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "content-type" to "application/json",
                ),
                bodyJson = body,
                onEvent = { data ->
                    runCatching {
                        val json = Json.parseToJsonElement(data).jsonObject
                        when (json["type"]?.jsonPrimitive?.content) {
                            "content_block_delta" -> {
                                val delta = json["delta"]?.jsonObject
                                val text = delta?.get("text")?.jsonPrimitive?.content
                                if (text != null) trySend(StreamEvent.TextDelta(text))
                            }
                            "message_stop" -> trySend(StreamEvent.Done)
                        }
                    }
                },
                onFailure = { t, _ -> trySend(StreamEvent.Error(t.message ?: "network error")); close() },
                onClosed = { trySend(StreamEvent.Done); close() },
            )

            awaitClose { }
        }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.addJsonObject(
    builderAction: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    add(buildJsonObject(builderAction))
}
