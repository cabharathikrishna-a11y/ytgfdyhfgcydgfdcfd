package com.example

import android.app.Application
import androidx.work.Configuration
import java.util.concurrent.Executors

class MainApplication : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(8))
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.util.AppCrashRollbackManager.initialize(this)
        com.example.api.Firebase.ensureFirebaseInitialized(this)
        com.example.util.UrgentNotificationHelper.initChannels(this)
    }

    companion object {
        lateinit var instance: MainApplication
            private set
    }
}
