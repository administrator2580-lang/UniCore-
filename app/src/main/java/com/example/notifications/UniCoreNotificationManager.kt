package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object UniCoreNotificationManager {

    const val CHANNEL_ID = "unicore_academic_alerts_v2"
    const val CHANNEL_SILENT_ID = "unicore_academic_alerts_silent_v2"
    private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L

    fun customSoundUri(context: Context): Uri {
        return Uri.parse("android.resource://${context.packageName}/${R.raw.unicore_chime}")
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    setSound(customSoundUri(context), audioAttrs)
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(CHANNEL_SILENT_ID) == null) {
                val silentChannel = NotificationChannel(
                    CHANNEL_SILENT_ID,
                    "${context.getString(R.string.notification_channel_name)} (Silent)",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    setSound(null, null)
                }
                manager.createNotificationChannel(silentChannel)
            }
        }
    }

    fun playChimePreview(context: Context) {
        runCatching {
            val ringtone = RingtoneManager.getRingtone(context, customSoundUri(context))
            ringtone?.play()
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showSystemNotification(
        context: Context,
        notificationIdKey: String,
        title: String,
        body: String,
        soundEnabled: Boolean = true
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationIdKey.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetChannel = if (soundEnabled) CHANNEL_ID else CHANNEL_SILENT_ID
        val builder = NotificationCompat.Builder(context, targetChannel)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (soundEnabled) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (soundEnabled) {
            builder.setSound(customSoundUri(context))
        } else {
            builder.setSilent(true)
        }

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationIdKey.hashCode(), builder.build())
        }
    }

    fun scheduleAssignmentReminder24h(
        context: Context,
        assignmentId: String,
        title: String,
        body: String,
        dueAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (dueAtMillis <= nowMillis) return
        val triggerAtMillis = (dueAtMillis - ONE_DAY_MILLIS).coerceAtLeast(nowMillis + 3_000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            putExtra(AssignmentReminderReceiver.EXTRA_ASSIGNMENT_ID, assignmentId)
            putExtra(AssignmentReminderReceiver.EXTRA_TITLE, title)
            putExtra(AssignmentReminderReceiver.EXTRA_BODY, body)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            ("due_24h_$assignmentId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancelAssignmentReminder24h(context: Context, assignmentId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AssignmentReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            ("due_24h_$assignmentId").hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        runCatching { alarmManager.cancel(pi) }
    }
}
