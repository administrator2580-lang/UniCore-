package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AssignmentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val assignmentId = intent?.getStringExtra(EXTRA_ASSIGNMENT_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()
        if (assignmentId.isBlank() || title.isBlank()) return

        UniCoreNotificationManager.showSystemNotification(
            context = context,
            notificationIdKey = "due_24h_$assignmentId",
            title = title,
            body = body.ifBlank { title },
            soundEnabled = true
        )
    }

    companion object {
        const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
    }
}
