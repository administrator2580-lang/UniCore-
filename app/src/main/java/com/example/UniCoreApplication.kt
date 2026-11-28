package com.example

import android.app.Application
import android.util.Log
import com.example.notifications.UniCoreNotificationManager
import com.google.firebase.FirebaseApp

/**
 * Custom Application entry point that ensures FirebaseApp and notification channels
 * are initialized safely without blocking or crashing cold startup.
 */
class UniCoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        }.onFailure { err ->
            Log.e(TAG, "Safe FirebaseApp initialization failed", err)
        }

        runCatching {
            UniCoreNotificationManager.ensureChannel(this)
        }.onFailure { err ->
            Log.w(TAG, "Safe notification channel setup failed", err)
        }
    }

    private companion object {
        const val TAG = "UniCoreApplication"
    }
}
