package com.snip.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snip.app.SnipApplication
import com.snip.app.ai.ChatRole
import com.snip.app.ui.theme.SnipTheme

class ChatActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(application as SnipApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it >= 0 }

        if (imagePath != null) {
            viewModel.start(imagePath, prompt, conversationId)
        } else if (conversationId != null) {
            viewModel.open(conversationId)
        }

        setContent {
            SnipTheme {
                Surface { ChatScreen(viewModel) }
            }
        }
    }

    companion object {
        private const val EXTRA_IMAGE_PATH = "extra_image_path"
        private const val EXTRA_PROMPT = "extra_prompt"
        private const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

        fun start(context: Context, imagePath: String, prompt: String, conversationId: Long?) {
            val intent = Intent(context, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_IMAGE_PATH, imagePath)
                .putExtra(EXTRA_PROMPT, prompt)
            if (conversationId != null) intent.putExtra(EXTRA_CONVERSATION_ID, conversationId)
            context.startActivity(intent)
        }

        /** Opens an existing conversation to view/continue it, with no new capture. */
        fun openExisting(context: Context, conversationId: Long) {
            val intent = Intent(context, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            context.startActivity(intent)
        }
    }
}

@Composable
private fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var followUp by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.missingApiKey) {
                Card(Modifier.padding(16.dp)) {
                    Text(
                        "${state.provider.label} API 키가 설정되어 있지 않습니다. 설정 화면에서 먼저 입력해주세요.",
                        Modifier.padding(12.dp),
                    )
                }
            }

            LazyColumn(Modifier.fillMaxSize().weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages) { message ->
                    MessageBubble(message.role, message.text)
                }
                if (state.isStreaming) {
                    item { CircularProgressIndicator(Modifier.padding(8.dp)) }
                }
            }

            state.error?.let { error ->
                Text(error, color = androidx.compose.ui.graphics.Color.Red, modifier = Modifier.padding(8.dp))
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = followUp,
                    onValueChange = { followUp = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("추가 질문...") },
                )
                Button(
                    onClick = {
                        viewModel.sendFollowUp(followUp)
                        followUp = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF3A3D46),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) { Text("보내기") }
            }
        }
    }
}

// User turns stay a right-aligned bubble (there's no reason to change how your own messages
// look). Assistant turns are the actual "answer" — other AI apps (Claude, ChatGPT) render that
// as plain full-width text with no bounding box, not a chat bubble, so match that instead of
// making replies look like a messenger conversation.
@Composable
private fun MessageBubble(role: ChatRole, text: String) {
    val textColor = MaterialTheme.colorScheme.onSurface
    if (role == ChatRole.USER) {
        Box(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(4.dp),
            ) {
                MarkdownText(text, color = textColor, modifier = Modifier.padding(12.dp))
            }
        }
    } else {
        MarkdownText(
            text,
            color = textColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
