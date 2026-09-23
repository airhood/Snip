package com.snip.app.ai

import kotlinx.coroutines.flow.Flow

enum class AiProvider(val label: String, val defaultModel: String) {
    ANTHROPIC("Claude", "claude-sonnet-5"),
    OPENAI("ChatGPT", "gpt-5.1"),
    GEMINI("Gemini", "gemini-2.5-flash"),
    OPENROUTER("OpenRouter", "anthropic/claude-sonnet-5"),
}

/** One image, base64-encoded, no data: prefix. */
data class ImageAttachment(val base64Png: String)

enum class ChatRole { USER, ASSISTANT }

data class ChatTurn(
    val role: ChatRole,
    val text: String,
    val images: List<ImageAttachment> = emptyList(),
)

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data object Done : StreamEvent()
}

interface AiClient {
    /** Streams the assistant's reply for [history] (last turn is the new user message). */
    fun streamReply(apiKey: String, model: String, history: List<ChatTurn>): Flow<StreamEvent>
}

fun aiClientFor(provider: AiProvider): AiClient = when (provider) {
    AiProvider.ANTHROPIC -> AnthropicClient()
    AiProvider.OPENAI -> OpenAiClient(baseUrl = "https://api.openai.com/v1", providerLabel = "ChatGPT")
    AiProvider.OPENROUTER -> OpenAiClient(baseUrl = "https://openrouter.ai/api/v1", providerLabel = "OpenRouter")
    AiProvider.GEMINI -> GeminiClient()
}
