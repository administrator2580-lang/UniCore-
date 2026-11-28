package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.notifications.UniCoreNotificationManager
import com.google.firebase.FirebaseApp

/**
 * Tracks cold-start crashes cleanly so if an external SDK (such as AdMob or WebView)
 * crashes immediately after splash, the next launch isolates non-essential ad initialization
 * and exposes a diagnostic report directly in-app.
 */
object StartupCrashGuard {
    private const val PREFS_NAME = "unicore_startup_guard"
    private const val KEY_LAST_CRASH = "last_crash_summary"
    private const val KEY_ISOLATE_ADS = "isolate_ads_safe_mode"
    private const val KEY_LAUNCH_TIMESTAMP = "last_launch_ms"
    private const val STARTUP_WINDOW_MS = 6_000L

    fun recordLaunchStart(context: Context) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAUNCH_TIMESTAMP, System.currentTimeMillis()).apply()
        }
    }

    fun recordFatalCrash(context: Context, threadName: String, error: Throwable) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val launchMs = prefs.getLong(KEY_LAUNCH_TIMESTAMP, 0L)
            val crashedDuringStartup = launchMs > 0L && (System.currentTimeMillis() - launchMs) <= STARTUP_WINDOW_MS
            val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
            val trace = root.stackTrace.take(6).joinToString("\n") { frame ->
                "  at ${frame.className.substringAfterLast('.')}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
            }
            val summary = buildString {
                append("${error::class.java.simpleName}: ${error.message ?: "Unknown error"}")
                if (root !== error) {
                    append("\nCause: ${root::class.java.simpleName}: ${root.message ?: "Unknown"}")
                }
                append("\nThread: $threadName")
                if (trace.isNotBlank()) {
                    append("\n$trace")
                }
            }.take(1200)

            prefs.edit()
                .putString(KEY_LAST_CRASH, summary)
                .apply {
                    if (crashedDuringStartup) {
                        putBoolean(KEY_ISOLATE_ADS, true)
                    }
                }
                .commit()
        }
    }

    fun isAdIsolationActive(context: Context): Boolean {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ISOLATE_ADS, false)
        }.getOrDefault(false)
    }

    fun getLastCrashSummary(context: Context): String? {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CRASH, null)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun clearCrashStateAndResumeAds(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .putBoolean(KEY_ISOLATE_ADS, false)
                .apply()
        }
    }

    fun dismissLastCrashOnly(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }
}

/**
 * Custom Application entry point that ensures FirebaseApp and notification channels
 * are initialized safely without blocking or crashing cold startup.
 */
class UniCoreApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        StartupCrashGuard.recordLaunchStart(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            StartupCrashGuard.recordFatalCrash(applicationContext, thread.name, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

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
