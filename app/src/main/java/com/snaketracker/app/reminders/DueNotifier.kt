package com.snaketracker.app.reminders

import android.content.Context
import com.snaketracker.app.logging.FileLogger
import java.time.LocalDate

/**
 * Remembers, per snake, the last reminder occurrence ([ReminderPlanner.occurrenceDayFor])
 * that was notified, so each snake is notified at most once per reminder day no
 * matter how many entries (alarm, launch, boot, clock change) race to post it.
 * The claim is atomic across threads: the receivers and the app-scope observer
 * all run in this one process.
 */
internal class DueLedger(
    private val lastClaimedDay: (snakeId: Long) -> Long?,
    private val record: (snakeId: Long, epochDay: Long) -> Unit
) {
    /** True exactly once per snake per [day]; later days claim again. */
    fun claim(snakeId: Long, day: LocalDate): Boolean = synchronized(LOCK) {
        val epochDay = day.toEpochDay()
        val last = lastClaimedDay(snakeId)
        if (last != null && last >= epochDay) {
            false
        } else {
            record(snakeId, epochDay)
            true
        }
    }

    companion object {
        private val LOCK = Any()
        private const val PREFS_FILE = "reminder_notified"

        fun forContext(context: Context): DueLedger {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            fun key(id: Long) = "snake_$id"
            return DueLedger(
                lastClaimedDay = { id -> if (prefs.contains(key(id))) prefs.getLong(key(id), 0L) else null },
                // commit(): the claim must be visible to the next caller immediately.
                record = { id, day -> prefs.edit().putLong(key(id), day).commit() }
            )
        }
    }
}

/** The one door every "feeding due" post goes through. */
internal object DueNotifier {
    fun notifyOnce(context: Context, snake: FrozenDueSnake, day: LocalDate, source: String) {
        if (!DueLedger.forContext(context).claim(snake.snakeId, day)) {
            FileLogger.i(
                "Notify",
                "SUPPRESSED duplicate snake=${snake.snakeId}:${snake.name} day=$day source=$source"
            )
            return
        }
        NotificationHelper.showFeedingDueNotification(
            context, snake.snakeId, snake.name, source = source
        )
    }
}
