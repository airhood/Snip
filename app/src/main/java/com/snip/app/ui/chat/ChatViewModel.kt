package com.snip.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snip.app.SnipApplication
import com.snip.app.ai.AiProvider
import com.snip.app.ai.ChatRole
import com.snip.app.ai.ChatTurn
import com.snip.app.ai.ImageAttachment
import com.snip.app.ai.StreamEvent
import com.snip.app.ai.aiClientFor
import com.snip.app.capture.CaptureStore
import com.snip.app.data.db.ConversationEntity
import com.snip.app.data.db.MessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class DisplayMessage(val role: ChatRole, val text: String, val imagePath: String?)

data class ChatUiState(
    val provider: AiProvider = AiProvider.ANTHROPIC,
    val model: String = "",
    val messages: List<DisplayMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val missingApiKey: Boolean = false,
)

class ChatViewModel(private val app: SnipApplication) : ViewModel() {

    private var conversationId: Long? = null
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    fun start(imagePath: String, initialPrompt: String, existingConversationId: Long?) {
        viewModelScope.launch {
            val settings = app.settingsRepository.settings.first()
            val provider = settings.activeProvider
            val model = settings.modelByProvider[provider] ?: provider.defaultModel
            _state.value = _state.value.copy(provider = provider, model = model)

            if (!app.apiKeyStore.hasKey(provider)) {
                _state.value = _state.value.copy(missingApiKey = true)
                return@launch
            }

            conversationId = existingConversationId ?: createConversation(provider, model)
            val history = loadHistory(conversationId!!)
            _state.value = _state.value.copy(messages = history)

            sendUserTurn(text = initialPrompt, imagePath = imagePath)
        }
    }

    /** Loads an existing conversation to view/continue it — no new capture involved. */
    fun open(existingConversationId: Long) {
        viewModelScope.launch {
            val conversation = app.database.conversationDao().getById(existingConversationId) ?: return@launch
            val provider = runCatching { AiProvider.valueOf(conversation.provider) }.getOrDefault(AiProvider.ANTHROPIC)
            conversationId = existingConversationId
            _state.value = _state.value.copy(
                provider = provider,
                model = conversation.model,
                messages = loadHistory(existingConversationId),
                missingApiKey = !app.apiKeyStore.hasKey(provider),
            )
        }
    }

    fun sendFollowUp(text: String) {
        if (text.isBlank()) return
        sendUserTurn(text = text, imagePath = null)
    }

    private fun sendUserTurn(text: String, imagePath: String?) {
        viewModelScope.launch {
            val cid = conversationId ?: return@launch
            val provider = _state.value.provider
            val model = _state.value.model
            val apiKey = app.apiKeyStore.getKey(provider) ?: return@launch
            val isFirstMessage = _state.value.messages.isEmpty()

            persistMessage(cid, ChatRole.USER, text, imagePath)
            touchConversation(cid, newTitle = if (isFirstMessage) text.take(40) else null)
            _state.value = _state.value.copy(
                messages = _state.value.messages + DisplayMessage(ChatRole.USER, text, imagePath),
                isStreaming = true,
                error = null,
            )

            val turns = buildTurnsForRequest(cid)
            val client = aiClientFor(provider)
            val builder = StringBuilder()
            // Every provider client fires Done twice on a normal finish — once for the
            // explicit completion event/marker, again from the connection's onClosed — so
            // guard against persisting (and displaying) the same reply a second time.
            var persisted = false
            client.streamReply(apiKey, model, turns).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        builder.append(event.text)
                        updateStreamingAssistantMessage(builder.toString())
                    }
                    is StreamEvent.Error -> {
                        _state.value = _state.value.copy(isStreaming = false, error = event.message)
                    }
                    is StreamEvent.Done -> {
                        _state.value = _state.value.copy(isStreaming = false)
                        if (!persisted && builder.isNotEmpty()) {
                            persisted = true
                            persistMessage(cid, ChatRole.ASSISTANT, builder.toString(), null)
                        }
                    }
                }
            }
        }
    }

    private fun updateStreamingAssistantMessage(text: String) {
        val current = _state.value.messages
        val last = current.lastOrNull()
        _state.value = if (last?.role == ChatRole.ASSISTANT) {
            _state.value.copy(messages = current.dropLast(1) + DisplayMessage(ChatRole.ASSISTANT, text, null))
        } else {
            _state.value.copy(messages = current + DisplayMessage(ChatRole.ASSISTANT, text, null))
        }
    }

    private suspend fun buildTurnsForRequest(conversationId: Long): List<ChatTurn> {
        val entities = app.database.messageDao().getForConversation(conversationId)
        return entities.map { entity ->
            val images = entity.imagePath?.let { CaptureStore.base64PngFromPath(it) }
                ?.let { listOf(ImageAttachment(it)) } ?: emptyList()
            ChatTurn(
                role = if (entity.role == "USER") ChatRole.USER else ChatRole.ASSISTANT,
                text = entity.text,
                images = images,
            )
        }
    }

    private suspend fun loadHistory(conversationId: Long): List<DisplayMessage> =
        app.database.messageDao().getForConversation(conversationId).map {
            DisplayMessage(if (it.role == "USER") ChatRole.USER else ChatRole.ASSISTANT, it.text, it.imagePath)
        }

    private suspend fun createConversation(provider: AiProvider, model: String): Long =
        app.database.conversationDao().insert(
            ConversationEntity(
                title = "새 대화 ${System.currentTimeMillis()}",
                provider = provider.name,
                model = model,
                updatedAt = System.currentTimeMillis(),
            ),
        )

    /** updatedAt only got set once at creation, so the history list never re-sorted by
     * actual last activity — every follow-up needs to bump it, not just the first message. */
    private suspend fun touchConversation(conversationId: Long, newTitle: String?) {
        val existing = app.database.conversationDao().getById(conversationId) ?: return
        app.database.conversationDao().update(
            existing.copy(
                title = newTitle?.takeIf { it.isNotBlank() } ?: existing.title,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun persistMessage(conversationId: Long, role: ChatRole, text: String, imagePath: String?) {
        app.database.messageDao().insert(
            MessageEntity(
                conversationId = conversationId,
                role = role.name,
                text = text,
                imagePath = imagePath,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    class Factory(private val app: SnipApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(app) as T
    }
}
