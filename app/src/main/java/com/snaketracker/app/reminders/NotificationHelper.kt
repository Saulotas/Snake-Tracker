package com.snaketracker.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.snaketracker.app.MainActivity
import com.snaketracker.app.R

object NotificationHelper {
    // The HIGH-importance channel id (issue #38). The platform merges only a
    // *lower* importance into an existing channel record, and deleting a
    // channel only tombstones that record (a re-create keeps the old value),
    // so the raised banner importance ships under this fresh id: its first
    // creation is a full record at HIGH on new installs and in-place
    // upgrades alike. One creation site: SnakeTrackerApp.onCreate.
    const val CHANNEL_ID = "feeding_reminders_v2"

    // Id shipped at IMPORTANCE_DEFAULT before #38; retired by createChannel
    // so exactly one channel stays active after an upgrade.
    const val LEGACY_CHANNEL_ID = "feeding_reminders"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // A fresh record takes the constructor importance in full; an
            // existing HIGH record makes this a no-op merge.
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                }
                manager.createNotificationChannel(channel)
            }
            // Drop the pre-#38 DEFAULT-importance record on an upgrade.
            if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            }
        }
    }

    fun showFeedingDueNotification(context: Context, snakeId: Long, snakeName: String) {
        // POST_NOTIFICATIONS is a runtime permission on Android 13+; skip posting if the
        // user has not granted it, matching the app's "no notification when denied" intent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Tap-to-open: the content intent launches MainActivity (the launcher
        // activity); the request code is the per-snake notification id so each
        // snake's intent stays its own.
        val contentIntent = PendingIntent.getActivity(
            context,
            snakeId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_feeding_due_title, snakeName))
            .setContentText(context.getString(R.string.notification_feeding_due_text, snakeName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            // Auto-cancel: the tap lands in the log and clears the notification.
            .setAutoCancel(true)
            .build()

        // Each snake gets its own notification id so multiple reminders can stack;
        // re-posting the same id replaces that id's notification (notify-by-id rule).
        NotificationManagerCompat.from(context).notify(snakeId.toInt(), notification)
    }
}
