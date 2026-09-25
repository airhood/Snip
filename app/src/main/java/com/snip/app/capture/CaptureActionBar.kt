package com.snip.app.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.snip.app.dispatch.NativeTarget
import com.snip.app.ui.theme.SnipTheme
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RenderNode
import android.graphics.Shader as AndroidShader
import android.graphics.Paint as AndroidPaint
import android.os.Build

// One shared glow color everywhere — the same blue as GlowBorderView and the selection
// brackets — instead of a different accent per provider. Per-provider brand colors read as
// noise against a UI that's otherwise deliberately one consistent blue-teal language.
private val SnipGlow = Color(0xFF93BFF5)

/** No enclosing panel/card on purpose — a big opaque sheet ate into the screen and blocked
 * re-dragging a selection underneath it. Just the buttons themselves float over the capture,
 * each one individually glassy, with nothing behind them but the screenshot. */
@Composable
fun CaptureActionBar(visible: Boolean, content: @Composable () -> Unit) {
    SnipTheme {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } ,
            exit = slideOutVertically { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

/** Compact segmented control for picking rectangle-drag vs. freehand-pen selection. */
@Composable
fun ModeToggle(mode: SelectionMode, onModeChange: (SelectionMode) -> Unit) {
    SnipTheme {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xCC121218))
                .border(BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(50))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeToggleOption("사각형", selected = mode == SelectionMode.RECTANGLE) { onModeChange(SelectionMode.RECTANGLE) }
            ModeToggleOption("펜", selected = mode == SelectionMode.PEN) { onModeChange(SelectionMode.PEN) }
        }
    }
}

/** Round icon toggle for the freehand annotation tool — same glass/border language as
 * everything else, lit up in [SnipGlow] while active. */
@Composable
fun DrawToggleButton(enabled: Boolean, onToggle: () -> Unit) {
    SnipTheme {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) SnipGlow.copy(alpha = 0.35f) else Color(0xCC121218))
                .border(BorderStroke(1.6.dp, if (enabled) SnipGlow else Color(0x33FFFFFF)), CircleShape)
                .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            PenIcon(tint = if (enabled) Color.White else Color(0xFFC7CCDA), size = 20.dp)
        }
    }
}

/** Hand-drawn single-color pencil glyph — a rotated rounded body plus a triangular tip. The
 * emoji version wasn't actually a monochrome icon (it's a small colorful bitmap glyph), which
 * clashed with the rest of the UI's flat line-art language. */
@Composable
private fun PenIcon(tint: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(size)) {
        rotate(45f) {
            val w = size.toPx()
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.12f, w * 0.42f),
                size = ComposeSize(w * 0.56f, w * 0.16f),
                cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            )
            val tip = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.68f, w * 0.40f)
                lineTo(w * 0.68f, w * 0.60f)
                lineTo(w * 0.86f, w * 0.50f)
                close()
            }
            drawPath(tip, tint)
        }
    }
}

@Composable
private fun ModeToggleOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SnipGlow.copy(alpha = 0.30f) else Color.Transparent)
            .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF9AA0B4),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun NativeTargetRow(targets: List<NativeTarget>, onPick: (NativeTarget) -> Unit) {
    if (targets.isEmpty()) {
        GlassLabel("설치된 Claude / ChatGPT / Gemini 앱이 없어요")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        targets.forEach { target ->
            GlowPillButton(label = target.label, onClick = { onPick(target) })
        }
    }
}

/** A small standalone glass chip for status text — same language as the buttons, since there's
 * no panel behind it anymore to lean on for contrast. */
@Composable
private fun GlassLabel(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC121218))
            .border(BorderStroke(1.dp, Color(0x33FFFFFF)), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD4D7E0))
    }
}

/** Same recipe as the selection brackets and screen border: a blue rim, and behind it a real
 * Gaussian blur bloom (RenderNode + RenderEffect) of that same blue — not a fake translucent
 * tint pretending to glow, and not a different accent color per button. One consistent glow
 * language everywhere in this screen. */
@Composable
private fun GlowPillButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val glowArgb = SnipGlow.toArgb()
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(contentAlignment = Alignment.Center) {
        if (supportsBlur) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val node = RenderNode("pillGlow")
                        onDrawWithContent {
                            // A RenderNode's declared bounds are a hard clip on its composited
                            // output — TileMode only changes how blur *samples* at the edge, not
                            // whether output past the node's own size survives. Recording the
                            // shape flush with its bounds (as before) meant the blur had nowhere
                            // to bleed into and got cut off exactly at the pill's edge — the
                            // "square" the border/brackets never had, because those record into
                            // the whole screen-sized view with plenty of margin around the
                            // stroke. Here we manufacture that margin explicitly.
                            //
                            // Kept deliberately smaller than size.height-scaled: buttons sit only
                            // 12.dp apart, and a wide bloom reaches the next pill's own box, which
                            // draws on top of it (later in the row) and visually "cuts" the glow
                            // right at that neighbor's edge.
                            val blurRadius = 14.dp.toPx()
                            val margin = blurRadius * 1.3f
                            node.setPosition(-margin.toInt(), -margin.toInt(), (size.width + margin).toInt(), (size.height + margin).toInt())
                            val nodeCanvas = node.beginRecording()
                            // Lighter at the source, not just masked on top afterward — the fill
                            // Box sits directly over this same region, so its own tint alpha can
                            // only do so much against a full-strength blur showing through it.
                            val paint = AndroidPaint().apply { color = glowArgb; alpha = 130; isAntiAlias = true }
                            val r = size.height / 2f
                            nodeCanvas.drawRoundRect(margin, margin, margin + size.width, margin + size.height, r, r, paint)
                            node.endRecording()
                            node.setRenderEffect(AndroidRenderEffect.createBlurEffect(blurRadius, blurRadius, AndroidShader.TileMode.CLAMP))
                            drawIntoCanvas { it.nativeCanvas.drawRenderNode(node) }
                        }
                    },
            )
        }
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.16f))
                .background(SnipGlow.copy(alpha = 0.14f))
                .border(
                    BorderStroke(
                        1.6.dp,
                        Brush.verticalGradient(
                            listOf(Color(0xFFD2EBFF), SnipGlow.copy(alpha = 0.7f)),
                        ),
                    ),
                    shape,
                )
                .clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 13.dp),
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun PromptComposer(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onSendNew: () -> Unit,
    onSendContinue: () -> Unit,
    onContinueChatOnly: () -> Unit,
) {
    val fieldShape = RoundedCornerShape(20.dp)
    TextField(
        value = prompt,
        onValueChange = onPromptChange,
        modifier = Modifier
            .fillMaxWidth()
            .clip(fieldShape)
            .background(Color(0x14FFFFFF))
            .border(BorderStroke(1.dp, Color(0x2693BFF5)), fieldShape),
        placeholder = { Text("무엇을 물어볼까요?", color = Color(0xFF787E94)) },
        textStyle = LocalTextStyle.current.copy(color = Color.White),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Color(0xFF93BFF5),
        ),
    )
    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Sometimes there's nothing new worth capturing — just a follow-up question on an
        // existing conversation. This skips attaching the current screen entirely instead of
        // making that go through "이어서 보내기" (which always sends the capture along).
        GlowPillButton(label = "이어서 채팅", onClick = onContinueChatOnly)
        GlowPillButton(label = "이어서 보내기", onClick = onSendContinue)
        GlowPillButton(label = "새 채팅으로 보내기", onClick = onSendNew)
    }
}

/** Replaces the plain native AlertDialog.Builder list that used to pick which conversation to
 * continue — it was the one piece of this screen still in stock Android dialog styling instead
 * of the app's dark glass language. */
@Composable
fun ConversationPickerDialog(
    conversations: List<com.snip.app.data.db.ConversationEntity>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    SnipTheme {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF14151B))
                    .border(BorderStroke(1.dp, Color(0x1FFFFFFF)), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "이어서 보낼 대화 선택",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    conversations.forEach { conversation ->
                        ConversationPickerRow(conversation, onClick = { onPick(conversation.id) })
                    }
                }
                Text(
                    "취소",
                    color = Color(0xFF787E94),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationPickerRow(conversation: com.snip.app.data.db.ConversationEntity, onClick: () -> Unit) {
    val providerLabel = runCatching { com.snip.app.ai.AiProvider.valueOf(conversation.provider).label }
        .getOrDefault(conversation.provider)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0AFFFFFF))
            .border(BorderStroke(1.dp, Color(0x1FFFFFFF)), RoundedCornerShape(10.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(conversation.title, color = Color.White, fontWeight = FontWeight.Medium)
        Text("$providerLabel · ${conversation.model}", color = Color(0xFF787E94), style = MaterialTheme.typography.labelSmall)
    }
}
