package com.snaketracker.app.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One reminder-enabled snake and the instant of its most recent logged feeding,
 * or null when the snake has never been fed.
 */
data class ReminderCandidate(
    val snakeId: Long,
    val name: String,
    val feedingIntervalDays: Int,
    val lastFeedingAt: Instant?
)

/**
 * The planner's output: the snakes due right now and the single next instant at
 * which the planner should run again (null when there are no candidates).
 *
 * Since the frozen-payload seam (issue #37), production reads only
 * [nextAlarmAt] — the armed alarm's due set comes from [freezeDuePayload] —
 * so [dueSnakeIds] is consumed by `ReminderPlannerTest` alone, where it pins
 * the planner's due-now semantics directly.
 */
data class ReminderPlan(
    val dueSnakeIds: Set<Long>,
    val nextAlarmAt: Instant?
)

/**
 * Pure due-time engine for feeding reminders — no Android types, no I/O. "Now"
 * and the timezone are parameters, so every outcome is deterministic.
 *
 * Semantics: a fed snake's due instant is the calendar date of its last feeding
 * (in the given zone) plus its interval, normalized to 09:00 local time. A
 * never-fed snake is due immediately. A snake already due (overdue, still
 * unfed) stays due-now while its next-alarm contribution rolls to the next
 * 09:00, yielding a daily re-reminder until it is fed.
 */
object ReminderPlanner {
    private const val DUE_HOUR = 9

    fun plan(candidates: List<ReminderCandidate>, now: Instant, zone: ZoneId): ReminderPlan {
        val dueIds = mutableSetOf<Long>()
        var nextAlarm: Instant? = null

        for (candidate in candidates) {
            val last = candidate.lastFeedingAt
            if (last == null) {
                dueIds += candidate.snakeId
                nextAlarm = earlierOf(nextAlarm, nextAlarmAfter(now, zone))
            } else {
                val dueAt = dueInstantFor(last, candidate.feedingIntervalDays, zone)
                if (!dueAt.isAfter(now)) {
                    dueIds += candidate.snakeId
                    nextAlarm = earlierOf(nextAlarm, nextAlarmAfter(now, zone))
                } else {
                    nextAlarm = earlierOf(nextAlarm, dueAt)
                }
            }
        }
        return ReminderPlan(dueIds, nextAlarm)
    }

    // Calendar date of the last feeding (in `zone`) plus the interval, normalized
    // to DUE_HOUR local time. Shared with the payload freeze, which selects the
    // snakes due at the armed instant (issue #37).
    internal fun dueInstantFor(lastFeedingAt: Instant, intervalDays: Int, zone: ZoneId): Instant {
        val feedingDate = lastFeedingAt.atZone(zone).toLocalDate()
        val dueDate = feedingDate.plusDays(intervalDays.toLong())
        return ZonedDateTime.of(dueDate, LocalTime.of(DUE_HOUR, 0), zone).toInstant()
    }

    // The next DUE_HOUR local time strictly after `now` — today's if it has not
    // passed yet, otherwise tomorrow's.
    private fun nextAlarmAfter(now: Instant, zone: ZoneId): Instant {
        val todayAtDueHour = now.atZone(zone).with(LocalTime.of(DUE_HOUR, 0))
        return if (todayAtDueHour.toInstant().isAfter(now)) {
            todayAtDueHour.toInstant()
        } else {
            todayAtDueHour.plusDays(1).toInstant()
        }
    }

    private fun earlierOf(current: Instant?, candidate: Instant): Instant =
        if (current == null || candidate.isBefore(current)) candidate else current
}
