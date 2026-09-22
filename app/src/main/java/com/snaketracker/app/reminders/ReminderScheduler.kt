package com.snaketracker.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

/**
 * The exact-alarm permission ladder (pure, unit-tested): on platforms older than
 * Android 12 the exact API needs no permission; on Android 12+ it is used only
 * when SCHEDULE_EXACT_ALARM is held, otherwise the caller falls back to the
 * permission-free inexact API.
 */
internal fun shouldUseExactApi(sdkInt: Int, exactPermissionHeld: Boolean): Boolean =
    sdkInt < Build.VERSION_CODES.S || exactPermissionHeld

/**
 * Whether the exact-alarm permission is actually held on this platform
 * (platform seam: false below Android 12, where the exact API needs no
 * permission; the [AlarmManager.canScheduleExactAlarms] probe above it) —
 * the one source of truth behind the arm ladder and the launch prompt gate.
 */
internal fun exactPermissionHeld(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/**
 * The arm call behind the ladder (JVM-testable seam): when the ladder allows
 * the exact API it is tried first, degrading to [setInexact] if the exact set
 * throws SecurityException (permission revoked between the check and the set);
 * otherwise only [setInexact] runs.
 */
internal fun armViaPermissionLadder(
    sdkInt: Int,
    exactPermissionHeld: Boolean,
    setExact: () -> Unit,
    setInexact: () -> Unit
) {
    if (shouldUseExactApi(sdkInt, exactPermissionHeld)) {
        try {
            setExact()
            return
        } catch (_: SecurityException) {
            // Permission revoked between the check above and this call —
            // degrade to the inexact fallback instead of crashing.
        }
    }
    setInexact()
}

/**
 * The stated bound of the fallback window the ungranted rung arms with: the
 * alarm fires between the due instant and due + this length, so a device
 * without the exact-alarm grant still gets its reminder within 10 minutes of
 * the due hour (issue #36 WB2).
 */
internal const val FALLBACK_WINDOW_MILLIS = 10L * 60L * 1000L

/**
 * Arms exactly one alarm for the next feeding-due instant, freezing the plan's
 * due-now payload into the intent extras (issue #37 WB1) so the fire-time
 * notification path needs no database read. Exact while idle when the
 * permission ladder allows it (degrading to the bounded-window fallback if the
 * permission is revoked between check and set), otherwise a window alarm
 * bounded to [FALLBACK_WINDOW_MILLIS] — delivered inside the stated window
 * rather than at the next opportunistic wakeup.
 */
object ReminderScheduler {
    // The one request code of the single alarm; also the lookup key the
    // instrumented extras test reads the armed PendingIntent back with.
    internal const val ALARM_REQUEST_CODE = 4001

    fun reschedule(context: Context, payload: FrozenDuePayload) {
        val at = payload.nextAlarmAt
        if (at == null) {
            cancel(context)
        } else {
            arm(context, at, encodeDuePayload(payload))
        }
    }

    private fun arm(context: Context, at: Instant, extras: Map<String, String>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val operation = pendingIntent(context, extras)
        val triggerAtMillis = at.toEpochMilli()

        armViaPermissionLadder(
            sdkInt = Build.VERSION.SDK_INT,
            exactPermissionHeld = exactPermissionHeld(context),
            setExact = {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, operation
                )
            },
            setInexact = {
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    FALLBACK_WINDOW_MILLIS,
                    operation
                )
            }
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntent(context, emptyMap()))
    }

    private fun pendingIntent(context: Context, extras: Map<String, String>): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FEEDING_DUE)
        // The frozen due-now payload travels in the extras themselves, so the
        // fire-time receiver decodes a plain string map (issue #37 WB1).
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
