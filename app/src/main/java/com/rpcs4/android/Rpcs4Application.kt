package com.rpcs4.android

import android.app.Application
import com.rpcs4.android.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Rpcs4Application : Application() {

    /** Single application-wide scope for fire-and-forget work (settings writes, icon cache warmup). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Ensure the native runtime directories exist before anything touches them.
        applicationScope.launch {
            SettingsRepository.ensureInitialized(applicationContext)
        }
    }

    companion object {
        lateinit var instance: Rpcs4Application
            private set
    }
}
