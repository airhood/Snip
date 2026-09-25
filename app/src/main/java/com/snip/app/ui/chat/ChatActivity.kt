package com.snip.app.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snip.app.SnipApplication
import com.snip.app.ai.ChatRole
import com.snip.app.capture.CaptureStore
import com.snip.app.ui.theme.SnipTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var followUp by remember { mutableStateOf("") }
    var pendingImagePath by remember { mutableStateOf<String?>(null) }

    // The photo picker hands back a content:// Uri that only this launch can read; copy it into
    // our own cache immediately (same as a capture) so it survives and can be base64-encoded
    // for the API request the same way an on-screen capture is.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?.let { CaptureStore.saveAndGetPath(context, it) }
            }
            if (path != null) pendingImagePath = path
        }
    }

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
                    MessageBubble(message.role, message.text, message.imagePath)
                }
                if (state.isStreaming) {
                    item { CircularProgressIndicator(Modifier.padding(8.dp)) }
                }
            }

            state.error?.let { error ->
                Text(error, color = Color.Red, modifier = Modifier.padding(8.dp))
            }

            pendingImagePath?.let { path ->
                AttachmentPreview(path, onRemove = { pendingImagePath = null })
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachButton(onClick = {
                    pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })

                val fieldShape = RoundedCornerShape(20.dp)
                TextField(
                    value = followUp,
                    onValueChange = { followUp = it },
                    modifier = Modifier
                        .weight(1f)
                        .clip(fieldShape)
                        .background(Color(0x14FFFFFF))
                        .border(BorderStroke(1.dp, Color(0x2693BFF5)), fieldShape),
                    placeholder = { Text("추가 질문...", color = Color(0xFF787E94)) },
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF93BFF5),
                    ),
                )
                Button(
                    onClick = {
                        viewModel.sendFollowUp(followUp, pendingImagePath)
                        followUp = ""
                        pendingImagePath = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A3D46),
                        contentColor = Color.White,
                    ),
                ) { Text("보내기") }
            }
        }
    }
}

/** Borderless "+" to attach a photo — matches the glass/no-outline language of the rest of the
 * app instead of a boxed Material icon button. */
@Composable
private fun AttachButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .border(BorderStroke(1.dp, Color(0x2693BFF5)), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Color(0xFF93BFF5), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun AttachmentPreview(imagePath: String, onRemove: () -> Unit) {
    val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath)?.asImageBitmap() }
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Text(
            "이미지 첨부됨 — 지우기",
            color = Color(0xFF787E94),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRemove,
            ),
        )
    }
}

// User turns stay a right-aligned bubble (there's no reason to change how your own messages
// look). Assistant turns are the actual "answer" — other AI apps (Claude, ChatGPT) render that
// as plain full-width text with no bounding box, not a chat bubble, so match that instead of
// making replies look like a messenger conversation.
@Composable
private fun MessageBubble(role: ChatRole, text: String, imagePath: String? = null) {
    val textColor = MaterialTheme.colorScheme.onSurface
    if (role == ChatRole.USER) {
        Box(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(4.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imagePath != null) {
                        val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath)?.asImageBitmap() }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(160.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                    if (text.isNotBlank()) MarkdownText(text, color = textColor)
                }
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
