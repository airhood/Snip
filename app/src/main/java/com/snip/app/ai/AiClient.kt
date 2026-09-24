package com.snip.app.ai

import kotlinx.coroutines.flow.Flow

enum class AiProvider(val label: String, val defaultModel: String, val presetModels: List<String>) {
    ANTHROPIC(
        "Claude",
        "claude-sonnet-5",
        listOf(
            "claude-opus-5",
            "claude-sonnet-5",
            "claude-fable-5-1",
            "claude-sonnet-4-6",
            "claude-haiku-4-5-20251001",
        ),
    ),
    OPENAI(
        "ChatGPT",
        "gpt-6-astra",
        listOf("gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-terra"),
    ),
    GEMINI(
        "Gemini",
        "gemini-3.8-flash",
        listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-pro", "gemini-2.5-flash"),
    ),
    OPENROUTER(
        "OpenRouter",
        "anthropic/claude-sonnet-5",
        listOf(
            "anthropic/claude-opus-5",
            "anthropic/claude-sonnet-5",
            "anthropic/claude-fable-5-1",
            "openai/gpt-6-astra",
            "openai/gpt-6-sol",
            "openai/gpt-6-luna",
            "google/gemini-3.8-flash",
            "google/gemini-3.1-pro",
        ),
    ),
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
