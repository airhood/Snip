package com.snip.app.capture

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.snip.app.SnipApplication
import com.snip.app.data.db.ConversationEntity
import com.snip.app.dispatch.NativeAppShare
import com.snip.app.dispatch.NativeTarget
import com.snip.app.settings.ResponseMode
import com.snip.app.ui.chat.ChatActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shown immediately after a screenshot is captured (from either the accessibility-service
 * edge-swipe or the assistant-role session). Lets the user drag a region, then dispatches
 * it either to a native AI app via Intent, or into our own chat UI.
 */
class RegionSelectActivity : ComponentActivity() {

    private lateinit var selectView: RegionSelectView

    // Visible by default — no selection means "send the whole screen", not "nothing to do yet".
    private var actionBarVisible by mutableStateOf(true)
    private var responseMode by mutableStateOf<ResponseMode?>(null)
    private var nativeTargets by mutableStateOf<List<NativeTarget>>(emptyList())
    private var prompt by mutableStateOf("")
    private var selectionMode by mutableStateOf(SelectionMode.RECTANGLE)
    private var drawModeEnabled by mutableStateOf(false)
    private var conversationChoices by mutableStateOf<List<ConversationEntity>>(emptyList())
    private var onConversationPicked by mutableStateOf<((Long) -> Unit)?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.apply {
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
        }

        val screenshot = PendingCapture.fullScreenshot
        if (screenshot == null) {
            finish()
            return
        }

        val root = FrameLayout(this)
        selectView = RegionSelectView(this).apply { setScreenshot(screenshot) }
        root.addView(selectView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val glowView = GlowBorderView(this)
        root.addView(glowView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        glowView.start()

        // RegionSelectView captures every touch for drag-select, which can swallow a gesture-nav
        // back swipe — so an explicit close button is the reliable way out of an accidental trigger.
        val closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1A1A1A"))
            }
            contentDescription = "닫기"
            setOnClickListener { finish() }
        }
        val closeButtonSizePx = (resources.displayMetrics.density * 44).toInt()
        val closeMarginPx = (resources.displayMetrics.density * 16).toInt()
        root.addView(
            closeButton,
            FrameLayout.LayoutParams(closeButtonSizePx, closeButtonSizePx).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(closeMarginPx, closeMarginPx, 0, 0)
            },
        )

        val modeToggle = ComposeView(this).apply {
            setContent {
                ModeToggle(
                    mode = selectionMode,
                    onModeChange = {
                        selectionMode = it
                        selectView.mode = it
                    },
                )
            }
        }
        val modeToggleTopMarginPx = (resources.displayMetrics.density * 48).toInt()
        root.addView(
            modeToggle,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, modeToggleTopMarginPx, closeMarginPx, 0)
            },
        )

        val drawToggle = ComposeView(this).apply {
            setContent {
                DrawToggleButton(
                    enabled = drawModeEnabled,
                    onToggle = {
                        drawModeEnabled = !drawModeEnabled
                        selectView.drawModeEnabled = drawModeEnabled
                    },
                )
            }
        }
        root.addView(
            drawToggle,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, closeMarginPx, closeMarginPx)
            },
        )

        val actionBar = ComposeView(this).apply {
            setContent {
                CaptureActionBar(visible = actionBarVisible) {
                    when (responseMode) {
                        ResponseMode.NATIVE_APP_INTENT -> NativeTargetRow(nativeTargets) { target ->
                            shareToNativeApp(target)
                        }
                        ResponseMode.OWN_API -> PromptComposer(
                            prompt = prompt,
                            onPromptChange = { prompt = it },
                            onSendNew = { sendToChat(prompt, conversationId = null) },
                            onSendContinue = {
                                requestConversationPick(
                                    onEmpty = { sendToChat(prompt, conversationId = null) },
                                    onPick = { id -> sendToChat(prompt, conversationId = id) },
                                )
                            },
                            onContinueChatOnly = {
                                requestConversationPick { id ->
                                    ChatActivity.openExisting(this@RegionSelectActivity, id)
                                    finish()
                                }
                            },
                        )
                        null -> Unit
                    }
                }
            }
        }
        root.addView(
            actionBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )

        val pickerOverlay = ComposeView(this).apply {
            setContent {
                onConversationPicked?.let { onPick ->
                    ConversationPickerDialog(
                        conversations = conversationChoices,
                        onPick = { id ->
                            onConversationPicked = null
                            onPick(id)
                        },
                        onDismiss = { onConversationPicked = null },
                    )
                }
            }
        }
        root.addView(pickerOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        // Hide only while actively dragging out a new selection (it would otherwise sit over
        // the bottom of the screen and eat the touches needed to draw there); reappears
        // regardless of whether that drag produced a selection, since "none" now just means
        // "send the whole screen".
        selectView.onDragStateChanged = { dragging -> actionBarVisible = !dragging }

        lifecycleScope.launch {
            val app = application as SnipApplication
            val settings = app.settingsRepository.settings.first()
            responseMode = settings.responseMode
            prompt = settings.defaultPrompt
            if (settings.responseMode == ResponseMode.NATIVE_APP_INTENT) {
                nativeTargets = NativeAppShare.installedTargets(this@RegionSelectActivity)
            }
            selectionMode = settings.defaultSelectionMode
            selectView.mode = settings.defaultSelectionMode
            selectView.annotationColor = settings.annotationColor
        }
    }

    private fun shareToNativeApp(target: NativeTarget) {
        val crop = selectView.cropSelection() ?: return
        val uri = CaptureStore.saveAndGetUri(this, crop)
        NativeAppShare.share(this, target, uri, prompt)
        finish()
    }

    /** Shared by "이어서 보내기" (attaches the capture) and "이어서 채팅" (does not) — both just
     * need the user to pick which saved conversation to continue. [onEmpty] covers the case
     * where there's nothing to continue: "이어서 보내기" falls back to starting a new chat with
     * the capture, but "이어서 채팅" has no capture to fall back to, so it just says so. */
    private fun requestConversationPick(onEmpty: () -> Unit = ::showNoConversationsToast, onPick: (Long) -> Unit) {
        lifecycleScope.launch {
            val app = application as SnipApplication
            val conversations = app.database.conversationDao().observeAll().first()
            if (conversations.isEmpty()) {
                onEmpty()
                return@launch
            }
            conversationChoices = conversations
            onConversationPicked = onPick
        }
    }

    private fun showNoConversationsToast() {
        Toast.makeText(this, "이어서 보낼 대화가 없어요", Toast.LENGTH_SHORT).show()
    }

    private fun sendToChat(prompt: String, conversationId: Long?) {
        val crop = selectView.cropSelection() ?: return
        val path = CaptureStore.saveAndGetPath(this, crop)
        ChatActivity.start(this, imagePath = path, prompt = prompt, conversationId = conversationId)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        PendingCapture.clear()
    }
}
