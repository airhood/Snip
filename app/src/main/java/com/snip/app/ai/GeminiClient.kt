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

class GeminiClient : AiClient {

    override fun streamReply(apiKey: String, model: String, history: List<ChatTurn>): Flow<StreamEvent> =
        callbackFlow {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    history.forEach { turn ->
                        add(buildJsonObject {
                            put("role", if (turn.role == ChatRole.USER) "user" else "model")
                            putJsonArray("parts") {
                                if (turn.text.isNotBlank()) {
                                    add(buildJsonObject { put("text", turn.text) })
                                }
                                turn.images.forEach { image ->
                                    add(buildJsonObject {
                                        put("inline_data", buildJsonObject {
                                            put("mime_type", "image/png")
                                            put("data", image.base64Png)
                                        })
                                    })
                                }
                            }
                        })
                    }
                }
            }.toString()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent" +
                "?alt=sse&key=$apiKey"

            streamSse(
                url = url,
                headers = mapOf("content-type" to "application/json"),
                bodyJson = body,
                onEvent = { data ->
                    runCatching {
                        val json = Json.parseToJsonElement(data).jsonObject
                        val candidate = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                        val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) trySend(StreamEvent.TextDelta(text))
                        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.content
                        if (finishReason != null) trySend(StreamEvent.Done)
                    }
                },
                onFailure = { t, _ -> trySend(StreamEvent.Error(t.message ?: "Gemini network error")); close() },
                onClosed = { trySend(StreamEvent.Done); close() },
            )

            awaitClose { }
        }
}
