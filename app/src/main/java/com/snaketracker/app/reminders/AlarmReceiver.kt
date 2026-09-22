package com.snaketracker.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Fired by the single armed alarm: the plan's due-now set was frozen into this
 * intent's extras at arm time, so the notification path posts straight from
 * the decoded extras — no database read (issue #37 WB2), using the existing
 * per-snake notification id so a re-post replaces rather than stacks. After
 * every post, the shared snapshot→plan pass re-arms the next instant or
 * cancels when there is none (issue #37 WB3). Extras that decode to no snakes
 * post nothing and still re-arm (issue #37 WB4).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FEEDING_DUE) return

        val payload = decodeDuePayload(intent.extras.toFlatStringMap())
        launchGoAsync(context) { appContext ->
            notifyFrozenPayloadAndReschedule(
                payload = payload,
                notify = { snake ->
                    NotificationHelper.showFeedingDueNotification(
                        appContext, snake.snakeId, snake.name
                    )
                },
                reschedule = { ReminderArming.reschedule(appContext) }
            )
        }
    }

    companion object {
        const val ACTION_FEEDING_DUE = "com.snaketracker.app.reminders.ACTION_FEEDING_DUE"
    }
}

/** The Bundle edge for the pure [decodeDuePayload] seam: string extras as a plain map. */
private fun Bundle?.toFlatStringMap(): Map<String, String?> {
    this ?: return emptyMap()
    val map = mutableMapOf<String, String?>()
    for (key in keySet()) {
        map[key] = getString(key)
    }
    return map
}
