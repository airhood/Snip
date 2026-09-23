package com.snip.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.snip.app.ai.AiProvider

/** Stores per-provider API keys encrypted with an Android Keystore-backed master key. */
class ApiKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "snip_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getKey(provider: AiProvider): String? = prefs.getString(provider.name, null)?.takeIf { it.isNotBlank() }

    fun setKey(provider: AiProvider, key: String) {
        prefs.edit().putString(provider.name, key).apply()
    }

    fun clearKey(provider: AiProvider) {
        prefs.edit().remove(provider.name).apply()
    }

    fun hasKey(provider: AiProvider): Boolean = getKey(provider) != null
}
