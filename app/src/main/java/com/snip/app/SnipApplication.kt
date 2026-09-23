package com.snip.app

import android.app.Application
import com.snip.app.data.db.AppDatabase
import com.snip.app.settings.ApiKeyStore
import com.snip.app.settings.SettingsRepository

class SnipApplication : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(this) }
    val database: AppDatabase by lazy { AppDatabase.build(this) }

    companion object {
        lateinit var instance: SnipApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
