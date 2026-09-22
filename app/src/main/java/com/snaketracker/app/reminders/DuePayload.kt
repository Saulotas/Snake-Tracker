package com.snaketracker.app.reminders

import java.time.Instant
import java.time.ZoneId

/**
 * One frozen due-now snake: its id doubles as the notification id and its name
 * feeds the notification text, so the fire-time path needs nothing else.
 */
data class FrozenDueSnake(val snakeId: Long, val name: String)

/**
 * The frozen alarm payload: every due-now snake at arm time plus the next
 * alarm instant (null when there is none). Plain Kotlin, so the JVM test and
 * the extras map drive it directly.
 */
data class FrozenDuePayload(
    val dueSnakes: List<FrozenDueSnake>,
    val nextAlarmAt: Instant?
)

// Flat intent-extras keys of the frozen payload: a snake count, one
// `<id|name>` pair per index below it, and the next-alarm epoch millis.
internal const val DUE_SNAKE_COUNT_KEY = "due_snake_count"
internal const val NEXT_ALARM_AT_KEY = "next_alarm_at"
internal const val DUE_SNAKE_KEY_PREFIX = "due_snake_"

/**
 * Freezes the set due **at the armed instant** against the candidates the plan
 * was computed from: never-fed snakes plus every fed snake whose due instant
 * has passed by [ReminderPlan.nextAlarmAt] (so the fire at that instant posts
 * exactly what comes due then, with no one-day lag), in candidate order, plus
 * the plan's next-alarm instant (issue #37 WB1).
 */
internal fun freezeDuePayload(
    plan: ReminderPlan,
    candidates: List<ReminderCandidate>,
    zone: ZoneId
): FrozenDuePayload {
    val armedAt = plan.nextAlarmAt
    return FrozenDuePayload(
        dueSnakes = candidates
            .filter { candidate ->
                armedAt != null &&
                    (candidate.lastFeedingAt == null ||
                        ReminderPlanner.dueInstantFor(
                            candidate.lastFeedingAt, candidate.feedingIntervalDays, zone
                        ) <= armedAt)
            }
            .map { FrozenDueSnake(it.snakeId, it.name) },
        nextAlarmAt = armedAt
    )
}

/**
 * Encodes the frozen payload into a flat string map for the alarm's intent
 * extras. The absent [NEXT_ALARM_AT_KEY] means "no next alarm".
 */
internal fun encodeDuePayload(payload: FrozenDuePayload): Map<String, String> {
    val extras = mutableMapOf<String, String>()
    payload.dueSnakes.forEachIndexed { index, snake ->
        extras["$DUE_SNAKE_KEY_PREFIX${index}_id"] = snake.snakeId.toString()
        extras["$DUE_SNAKE_KEY_PREFIX${index}_name"] = snake.name
    }
    extras[DUE_SNAKE_COUNT_KEY] = payload.dueSnakes.size.toString()
    payload.nextAlarmAt?.let { extras[NEXT_ALARM_AT_KEY] = it.toEpochMilli().toString() }
    return extras
}

/**
 * Decodes the alarm intent's extras back into the frozen payload: the count
 * selects the snake entries, and any missing or unreadable entry simply drops
 * out. No extras at all decodes to the empty payload — no notifications, no
 * next instant — so the receiver can still re-arm from a fresh plan
 * (issue #37 WB4).
 */
internal fun decodeDuePayload(extras: Map<String, String?>): FrozenDuePayload {
    val count = extras[DUE_SNAKE_COUNT_KEY]?.toIntOrNull() ?: 0
    val dueSnakes = buildList {
        for (index in 0 until count) {
            val snakeId = extras["$DUE_SNAKE_KEY_PREFIX${index}_id"]?.toLongOrNull()
            val name = extras["$DUE_SNAKE_KEY_PREFIX${index}_name"]
            if (snakeId != null && name != null) add(FrozenDueSnake(snakeId, name))
        }
    }
    val nextAlarmAt = extras[NEXT_ALARM_AT_KEY]?.toLongOrNull()?.let(Instant::ofEpochMilli)
    return FrozenDuePayload(dueSnakes, nextAlarmAt)
}
