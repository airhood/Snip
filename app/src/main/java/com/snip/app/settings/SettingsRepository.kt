package com.snip.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.snip.app.ai.AiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "snip_settings")

enum class ActivationMode { ASSISTANT_ROLE, EDGE_SWIPE }
enum class ResponseMode { NATIVE_APP_INTENT, OWN_API }
enum class EdgeSide { LEFT, RIGHT }

data class SnipSettings(
    val activationMode: ActivationMode = ActivationMode.EDGE_SWIPE,
    val responseMode: ResponseMode = ResponseMode.NATIVE_APP_INTENT,
    val edgeSide: EdgeSide = EdgeSide.RIGHT,
    val defaultPrompt: String = "",
    val defaultNativeTargetPackage: String? = null,
    val activeProvider: AiProvider = AiProvider.ANTHROPIC,
    val modelByProvider: Map<AiProvider, String> = AiProvider.entries.associateWith { it.defaultModel },
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val ACTIVATION_MODE = stringPreferencesKey("activation_mode")
        val RESPONSE_MODE = stringPreferencesKey("response_mode")
        val EDGE_SIDE = stringPreferencesKey("edge_side")
        val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")
        val DEFAULT_NATIVE_TARGET = stringPreferencesKey("default_native_target")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider")
        fun modelKey(provider: AiProvider) = stringPreferencesKey("model_${provider.name}")
    }

    val settings: Flow<SnipSettings> = store.data.map { prefs ->
        SnipSettings(
            activationMode = prefs[Keys.ACTIVATION_MODE]?.let { runCatching { ActivationMode.valueOf(it) }.getOrNull() }
                ?: ActivationMode.EDGE_SWIPE,
            responseMode = prefs[Keys.RESPONSE_MODE]?.let { runCatching { ResponseMode.valueOf(it) }.getOrNull() }
                ?: ResponseMode.NATIVE_APP_INTENT,
            edgeSide = prefs[Keys.EDGE_SIDE]?.let { runCatching { EdgeSide.valueOf(it) }.getOrNull() }
                ?: EdgeSide.RIGHT,
            defaultPrompt = prefs[Keys.DEFAULT_PROMPT] ?: "",
            defaultNativeTargetPackage = prefs[Keys.DEFAULT_NATIVE_TARGET],
            activeProvider = prefs[Keys.ACTIVE_PROVIDER]?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                ?: AiProvider.ANTHROPIC,
            modelByProvider = AiProvider.entries.associateWith { provider ->
                prefs[Keys.modelKey(provider)] ?: provider.defaultModel
            },
        )
    }

    suspend fun setActivationMode(mode: ActivationMode) = store.edit { it[Keys.ACTIVATION_MODE] = mode.name }
    suspend fun setResponseMode(mode: ResponseMode) = store.edit { it[Keys.RESPONSE_MODE] = mode.name }
    suspend fun setEdgeSide(side: EdgeSide) = store.edit { it[Keys.EDGE_SIDE] = side.name }
    suspend fun setDefaultPrompt(prompt: String) = store.edit { it[Keys.DEFAULT_PROMPT] = prompt }
    suspend fun setDefaultNativeTarget(pkg: String?) = store.edit {
        if (pkg == null) it.remove(Keys.DEFAULT_NATIVE_TARGET) else it[Keys.DEFAULT_NATIVE_TARGET] = pkg
    }
    suspend fun setActiveProvider(provider: AiProvider) = store.edit { it[Keys.ACTIVE_PROVIDER] = provider.name }
    suspend fun setModel(provider: AiProvider, model: String) = store.edit { it[Keys.modelKey(provider)] = model }
}
