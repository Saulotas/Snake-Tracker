package com.snaketracker.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The pure action guard: the two clock broadcasts — a manual time edit and a
 * timezone change (DST included) — each call [refresh] exactly once; any
 * other intent (or a null action) is ignored, so unrelated broadcasts never
 * re-post the notification.
 */
internal fun handleClockChangeIntent(action: String?, refresh: () -> Unit) {
    when (action) {
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> refresh()
        else -> Unit
    }
}

/**
 * The system broadcasts these after the user edits the clock or the zone:
 * the armed instant was computed against the old local time, so the same
 * plan→notify→re-arm funnel the boot and exact-alarm-permission receivers
 * use runs again and the single alarm follows the new local time (09:00 in
 * the new zone). Failures at the data boundary are logged by the shared
 * goAsync scaffold without killing the process.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handleClockChangeIntent(intent.action) {
            launchGoAsync(context) { ReminderArming.refresh(it) }
        }
    }
}
