package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

enum class AppLanguage(val code: String) {
    ARABIC("ar"),
    ENGLISH("en");

    companion object {
        fun fromCode(raw: String?): AppLanguage {
            return entries.firstOrNull { it.code == raw }
                ?: if (Locale.getDefault().language == "ar") ARABIC else ENGLISH
        }
    }
}

enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    companion object {
        fun fromRaw(raw: String?): AppThemeMode {
            return entries.firstOrNull { it.name == raw } ?: SYSTEM
        }
    }
}

data class UserPreferences(
    val language: AppLanguage = if (Locale.getDefault().language == "ar") AppLanguage.ARABIC else AppLanguage.ENGLISH,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val systemNotificationsEnabled: Boolean = true,
    val notificationSoundEnabled: Boolean = true,
    val readNotificationIds: Set<String> = emptySet(),
    val scheduledReminderIds: Set<String> = emptySet()
)

private val Context.userPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "unicore_user_prefs"
)

class UserPreferencesRepository(private val context: Context) {

    val preferencesFlow: Flow<UserPreferences> = context.userPrefsDataStore.data.map { prefs ->
        UserPreferences(
            language = AppLanguage.fromCode(prefs[KEY_LANGUAGE]),
            themeMode = AppThemeMode.fromRaw(prefs[KEY_THEME_MODE]),
            systemNotificationsEnabled = prefs[KEY_SYSTEM_NOTIFICATIONS] ?: true,
            notificationSoundEnabled = prefs[KEY_SOUND_ENABLED] ?: true,
            readNotificationIds = prefs[KEY_READ_NOTIFICATION_IDS] ?: emptySet(),
            scheduledReminderIds = prefs[KEY_SCHEDULED_REMINDERS] ?: emptySet()
        )
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.userPrefsDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language.code
        }
    }

    suspend fun setThemeMode(themeMode: AppThemeMode) {
        context.userPrefsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = themeMode.name
        }
    }

    suspend fun setSystemNotificationsEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { prefs ->
            prefs[KEY_SYSTEM_NOTIFICATIONS] = enabled
        }
    }

    suspend fun setNotificationSoundEnabled(enabled: Boolean) {
        context.userPrefsDataStore.edit { prefs ->
            prefs[KEY_SOUND_ENABLED] = enabled
        }
    }

    suspend fun markNotificationRead(notificationId: String) {
        if (notificationId.isBlank()) return
        context.userPrefsDataStore.edit { prefs ->
            val current = prefs[KEY_READ_NOTIFICATION_IDS] ?: emptySet()
            val updated = (current + notificationId).toList().takeLast(200).toSet()
            prefs[KEY_READ_NOTIFICATION_IDS] = updated
        }
    }

    suspend fun markAllNotificationsRead(notificationIds: Collection<String>) {
        if (notificationIds.isEmpty()) return
        context.userPrefsDataStore.edit { prefs ->
            val current = prefs[KEY_READ_NOTIFICATION_IDS] ?: emptySet()
            val updated = (current + notificationIds).toList().takeLast(200).toSet()
            prefs[KEY_READ_NOTIFICATION_IDS] = updated
        }
    }

    suspend fun markReminderScheduled(reminderKey: String) {
        if (reminderKey.isBlank()) return
        context.userPrefsDataStore.edit { prefs ->
            val current = prefs[KEY_SCHEDULED_REMINDERS] ?: emptySet()
            val updated = (current + reminderKey).toList().takeLast(200).toSet()
            prefs[KEY_SCHEDULED_REMINDERS] = updated
        }
    }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val KEY_SYSTEM_NOTIFICATIONS = booleanPreferencesKey("system_notifications_enabled")
        val KEY_SOUND_ENABLED = booleanPreferencesKey("notification_sound_enabled")
        val KEY_READ_NOTIFICATION_IDS = stringSetPreferencesKey("read_notification_ids")
        val KEY_SCHEDULED_REMINDERS = stringSetPreferencesKey("scheduled_assignment_reminders")
    }
}
