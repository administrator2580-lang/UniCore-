package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.notifications.UniCoreNotificationManager
import com.google.firebase.FirebaseApp
import java.io.File

/**
 * Tracks cold-start crashes from before ContentProviders (attachBaseContext) through UI startup.
 * - First startup crash automatically isolates AdMob on next launch.
 * - Repeated consecutive startup crashes enter full safe recovery mode (dependency-free UI)
 *   so the exact exception & stack trace can always be viewed and copied in-app.
 */
object StartupCrashGuard {
    private const val PREFS_NAME = "unicore_startup_guard"
    private const val KEY_LAST_CRASH = "last_crash_summary"
    private const val KEY_ISOLATE_ADS = "isolate_ads_safe_mode"
    private const val KEY_FULL_RECOVERY = "full_safe_recovery_mode"
    private const val KEY_CONSECUTIVE_CRASHES = "consecutive_startup_crashes"
    private const val KEY_LAUNCH_TIMESTAMP = "last_launch_ms"
    private const val CRASH_FILE_NAME = "unicore_startup_crash.log"
    private const val STARTUP_WINDOW_MS = 8_000L

    @Volatile
    private var handlerInstalled = false

    fun installUncaughtHandlerEarly(base: Context) {
        if (handlerInstalled) return
        handlerInstalled = true
        val safeContext = base.applicationContext ?: base
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordFatalCrash(safeContext, thread.name, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordLaunchStart(context: Context) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAUNCH_TIMESTAMP, System.currentTimeMillis()).commit()
        }
    }

    fun recordFatalCrash(context: Context, threadName: String, error: Throwable) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val launchMs = prefs.getLong(KEY_LAUNCH_TIMESTAMP, 0L)
            val crashedDuringStartup = launchMs == 0L || (System.currentTimeMillis() - launchMs) <= STARTUP_WINDOW_MS
            val root = generateSequence(error) { it.cause }.lastOrNull() ?: error
            val trace = root.stackTrace.take(8).joinToString("\n") { frame ->
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
            }.take(1800)

            runCatching {
                File(appCtx.filesDir, CRASH_FILE_NAME).writeText(summary)
            }

            val nextCount = if (crashedDuringStartup) {
                prefs.getInt(KEY_CONSECUTIVE_CRASHES, 0) + 1
            } else {
                0
            }

            val editor = prefs.edit()
                .putString(KEY_LAST_CRASH, summary)
                .putInt(KEY_CONSECUTIVE_CRASHES, nextCount)
            if (crashedDuringStartup) {
                editor.putBoolean(KEY_ISOLATE_ADS, true)
                if (nextCount >= 2) {
                    editor.putBoolean(KEY_FULL_RECOVERY, true)
                }
            }
            editor.commit()
        }
    }

    fun markHealthyLaunch(context: Context) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CONSECUTIVE_CRASHES, 0)
                .putBoolean(KEY_FULL_RECOVERY, false)
                .apply()
        }
    }

    fun isAdIsolationActive(context: Context): Boolean {
        return runCatching {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ISOLATE_ADS, false)
        }.getOrDefault(false)
    }

    fun isFullRecoveryMode(context: Context): Boolean {
        return runCatching {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FULL_RECOVERY, false)
        }.getOrDefault(false)
    }

    fun getLastCrashSummary(context: Context): String? {
        return runCatching {
            val appCtx = context.applicationContext ?: context
            val fromPrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CRASH, null)
                ?.takeIf { it.isNotBlank() }
            if (fromPrefs != null) {
                fromPrefs
            } else {
                val file = File(appCtx.filesDir, CRASH_FILE_NAME)
                if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    fun bootWithoutAds(context: Context) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ISOLATE_ADS, true)
                .putBoolean(KEY_FULL_RECOVERY, false)
                .putInt(KEY_CONSECUTIVE_CRASHES, 0)
                .commit()
        }
    }

    fun clearCrashStateAndResumeAds(context: Context) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            File(appCtx.filesDir, CRASH_FILE_NAME).delete()
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .putBoolean(KEY_ISOLATE_ADS, false)
                .putBoolean(KEY_FULL_RECOVERY, false)
                .putInt(KEY_CONSECUTIVE_CRASHES, 0)
                .commit()
        }
    }

    fun dismissLastCrashOnly(context: Context) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            File(appCtx.filesDir, CRASH_FILE_NAME).delete()
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }
}

/**
 * Custom Application entry point that hooks early crash protection in attachBaseContext
 * (before ContentProviders execute) and initializes Firebase/notification channels safely.
 */
class UniCoreApplication : Application() {

    override fun attachBaseContext(base: Context) {
        StartupCrashGuard.installUncaughtHandlerEarly(base)
        StartupCrashGuard.recordLaunchStart(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        StartupCrashGuard.installUncaughtHandlerEarly(this)
        StartupCrashGuard.recordLaunchStart(this)

        if (!StartupCrashGuard.isFullRecoveryMode(this)) {
            runCatching {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    FirebaseApp.initializeApp(this)
                }
            }.onFailure { err ->
                Log.e(TAG, "Safe FirebaseApp initialization failed", err)
            }
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
