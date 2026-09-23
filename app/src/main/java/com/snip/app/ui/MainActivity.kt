package com.snip.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.snip.app.SnipApplication
import com.snip.app.ai.AiProvider
import com.snip.app.capture.SelectionMode
import com.snip.app.settings.ActivationMode
import com.snip.app.settings.EdgeSide
import com.snip.app.settings.ResponseMode
import com.snip.app.settings.SnipSettings
import com.snip.app.ui.theme.SnipTheme
import kotlinx.coroutines.launch

// Shared palette — same language as the capture screen's glow (blue) and glass panels.
private val SnipBlue = Color(0xFF6FA8F0)
private val SurfaceDark = Color(0xFF14151B)
private val CardDark = Color(0xFF1B1D25)
private val CardBorder = Color(0x1FFFFFFF)
private val TextMuted = Color(0xFF8F94A3)

private val AnnotationColorPresets = listOf(
    0xFFFF5C5C.toInt(), // red
    0xFFFFC24B.toInt(), // amber
    0xFF4BE0A0.toInt(), // green
    0xFF6FA8F0.toInt(), // blue
    0xFFFFFFFF.toInt(), // white
    0xFF16171C.toInt(), // near-black
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SnipApplication
        setContent {
            SnipTheme {
                Surface(color = SurfaceDark, modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(app)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(app: SnipApplication) {
    val settings by app.settingsRepository.settings.collectAsState(initial = SnipSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(containerColor = SurfaceDark) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Snip", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text("화면을 캡처해서 AI에게 바로 물어보세요", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            }

            SettingsSection(title = "권한") {
                SnipOutlineButton(
                    label = "접근성 서비스 켜기",
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }

            SettingsSection(title = "활성화 방식") {
                SelectableCard(
                    label = "엣지 스와이프",
                    description = "플로팅 버튼 없이 화면 가장자리를 안쪽으로 스와이프",
                    selected = settings.activationMode == ActivationMode.EDGE_SWIPE,
                    onClick = { scope.launch { app.settingsRepository.setActivationMode(ActivationMode.EDGE_SWIPE) } },
                )
                SelectableCard(
                    label = "Assistant Role",
                    description = "기본 어시스턴트 앱 제스처(모서리 스와이프 · 홈 길게 누르기)",
                    selected = settings.activationMode == ActivationMode.ASSISTANT_ROLE,
                    onClick = { scope.launch { app.settingsRepository.setActivationMode(ActivationMode.ASSISTANT_ROLE) } },
                )

                if (settings.activationMode == ActivationMode.EDGE_SWIPE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        SegmentChip(
                            label = "왼쪽",
                            selected = settings.edgeSide == EdgeSide.LEFT,
                            onClick = { scope.launch { app.settingsRepository.setEdgeSide(EdgeSide.LEFT) } },
                            modifier = Modifier.weight(1f),
                        )
                        SegmentChip(
                            label = "오른쪽",
                            selected = settings.edgeSide == EdgeSide.RIGHT,
                            onClick = { scope.launch { app.settingsRepository.setEdgeSide(EdgeSide.RIGHT) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    SnipOutlineButton(
                        label = "기본 어시스턴트 앱 설정 열기",
                        onClick = { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                    )
                }
            }

            SettingsSection(title = "캡처 후 응답 방식") {
                SelectableCard(
                    label = "설치된 앱으로 전달",
                    description = "Claude / ChatGPT / Gemini — 구독 그대로 재사용",
                    selected = settings.responseMode == ResponseMode.NATIVE_APP_INTENT,
                    onClick = { scope.launch { app.settingsRepository.setResponseMode(ResponseMode.NATIVE_APP_INTENT) } },
                )
                SelectableCard(
                    label = "자체 API 연동",
                    description = "앱 안에서 바로 답변 표시 (API 키 필요)",
                    selected = settings.responseMode == ResponseMode.OWN_API,
                    onClick = { scope.launch { app.settingsRepository.setResponseMode(ResponseMode.OWN_API) } },
                )
            }

            if (settings.responseMode == ResponseMode.OWN_API) {
                SettingsSection(title = "API 설정") {
                    ApiSettingsSection(app, settings)
                }
            }

            SettingsSection(title = "캡처 화면") {
                Text("기본 선택 방식", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    SegmentChip(
                        label = "사각형",
                        selected = settings.defaultSelectionMode == SelectionMode.RECTANGLE,
                        onClick = { scope.launch { app.settingsRepository.setDefaultSelectionMode(SelectionMode.RECTANGLE) } },
                        modifier = Modifier.weight(1f),
                    )
                    SegmentChip(
                        label = "펜",
                        selected = settings.defaultSelectionMode == SelectionMode.PEN,
                        onClick = { scope.launch { app.settingsRepository.setDefaultSelectionMode(SelectionMode.PEN) } },
                        modifier = Modifier.weight(1f),
                    )
                }

                Text("그림 도구 색상", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnnotationColorPresets.forEach { presetArgb ->
                        val selected = settings.annotationColor == presetArgb
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(presetArgb))
                                .border(
                                    BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) Color.White else CardBorder),
                                    CircleShape,
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { scope.launch { app.settingsRepository.setAnnotationColor(presetArgb) } },
                                ),
                        )
                    }
                }
            }

            SettingsSection(title = "기본 프롬프트") {
                SnipTextField(
                    value = settings.defaultPrompt,
                    onValueChange = { scope.launch { app.settingsRepository.setDefaultPrompt(it) } },
                    placeholder = "Intent 방식일 때 가능하면 함께 전달돼요",
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A titled group of settings, rendered as a bordered card — the shadcn "Card" pattern. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope2.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardDark)
                .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ColumnScope2(this).content()
        }
    }
}

/** Thin wrapper so [SettingsSection]'s content lambda doesn't leak Compose's ColumnScope
 * receiver ambiguity when nesting — purely a readability aid here. */
private class ColumnScope2(private val scope: androidx.compose.foundation.layout.ColumnScope) {
    @Composable
    fun Placeholder() = Unit
}

@Composable
private fun SelectableCard(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SnipBlue.copy(alpha = 0.12f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) SnipBlue.copy(alpha = 0.6f) else CardBorder), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.5.dp, if (selected) SnipBlue else TextMuted), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(SnipBlue))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) SnipBlue.copy(alpha = 0.18f) else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) SnipBlue else CardBorder), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else TextMuted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SnipOutlineButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, SnipBlue.copy(alpha = 0.5f)), shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = SnipBlue, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SnipTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextMuted) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0x14FFFFFF),
            unfocusedContainerColor = Color(0x0AFFFFFF),
            focusedBorderColor = SnipBlue,
            unfocusedBorderColor = CardBorder,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = SnipBlue,
        ),
    )
}

/** Free text entry (any model string works) plus a dropdown of a few current presets per
 * provider — picking one just fills the field, it doesn't lock out typing something else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(provider: com.snip.app.ai.AiProvider, value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Starts read-only (tapping just opens the preset list, no keyboard). Only picking
    // "기타" below switches to a real editable field and pulls up the keyboard.
    var customMode by remember(provider) { mutableStateOf(value.isNotEmpty() && value !in provider.presetModels) }
    val focusRequester = remember { FocusRequester() }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!customMode) expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !customMode,
            label = { Text("모델", color = TextMuted) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().focusRequester(focusRequester),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0x14FFFFFF),
                unfocusedContainerColor = Color(0x0AFFFFFF),
                focusedBorderColor = SnipBlue,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = SnipBlue,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            provider.presetModels.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                        expanded = false
                        customMode = false
                        onValueChange(preset)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("기타 (직접 입력)", color = TextMuted) },
                onClick = {
                    expanded = false
                    customMode = true
                    onValueChange("")
                },
            )
        }
    }

    LaunchedEffect(customMode) {
        if (customMode) focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiSettingsSection(app: SnipApplication, settings: SnipSettings) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = settings.activeProvider.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("프로바이더", color = TextMuted) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0x14FFFFFF),
                unfocusedContainerColor = Color(0x0AFFFFFF),
                focusedBorderColor = SnipBlue,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        expanded = false
                        scope.launch { app.settingsRepository.setActiveProvider(provider) }
                    },
                )
            }
        }
    }

    val provider = settings.activeProvider
    var apiKeyText by remember(provider) { mutableStateOf(app.apiKeyStore.getKey(provider) ?: "") }
    var modelText by remember(provider) { mutableStateOf(settings.modelByProvider[provider] ?: provider.defaultModel) }

    ModelField(
        provider = provider,
        value = modelText,
        onValueChange = {
            modelText = it
            scope.launch { app.settingsRepository.setModel(provider, it) }
        },
    )

    SnipTextField(
        value = apiKeyText,
        onValueChange = {
            apiKeyText = it
            app.apiKeyStore.setKey(provider, it)
        },
        placeholder = "${provider.label} API 키",
        isPassword = true,
    )
}
