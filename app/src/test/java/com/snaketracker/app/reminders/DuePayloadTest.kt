package com.snaketracker.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The pure encode/decode seam between the due-now plan and the flat intent
 * extras map (issue #37 WB1/WB4): driven with plain maps, no Android types.
 */
class DuePayloadTest {

    // Fixed +02:00 zone so every expectation is a hand-computed literal.
    private val zone: ZoneId = ZoneId.of("GMT+02:00")

    // 2026-09-14 12:00 local — today's 09:00 reminder has already passed.
    private val now: Instant = Instant.parse("2026-09-14T10:00:00Z")

    // Overdue snake (id 1) plus a not-yet-due snake (id 2) and a snake that
    // rolls over tomorrow 09:00 local (id 3): the plan freezes ids 1 and 3.
    private val candidates = listOf(
        ReminderCandidate(1, "Noodle", 7, Instant.parse("2026-09-01T12:00:00Z")),
        ReminderCandidate(2, "Future", 7, Instant.parse("2026-09-13T18:00:00Z")),
        ReminderCandidate(3, "Coil", 0, null)
    )

    @Test
    fun freeze_takesEveryDueSnakeIdAndNameInCandidateOrder_plusTheNextInstant() {
        val plan = ReminderPlanner.plan(candidates, now, zone)

        val payload = freezeDuePayload(plan, candidates, zone)

        // Noodle (overdue) and Coil (never fed) are due now; Future is not.
        // Overdue at 12:00 local with today's 09:00 passed → next 09:00 tomorrow.
        assertEquals(
            FrozenDuePayload(
                dueSnakes = listOf(FrozenDueSnake(1, "Noodle"), FrozenDueSnake(3, "Coil")),
                nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
            ),
            payload
        )
    }

    @Test
    fun payload_listsTheSnakesDueAtTheArmedInstant() {
        // A single not-yet-due snake: fed 2026-09-12 20:00 local, interval 3 →
        // due 2026-09-15 09:00 local — the very instant the alarm is armed for.
        val soonOnly = listOf(
            ReminderCandidate(2, "Future", 3, Instant.parse("2026-09-12T18:00:00Z"))
        )
        val plan = ReminderPlanner.plan(soonOnly, now, zone)

        val payload = freezeDuePayload(plan, soonOnly, zone)

        // The fire at the armed instant must post the snake that becomes due
        // at that instant, not an empty list one day before it (issue #37 F1).
        assertEquals(
            FrozenDuePayload(
                dueSnakes = listOf(FrozenDueSnake(2, "Future")),
                nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
            ),
            payload
        )
    }

    @Test
    fun encodeDecodeRoundTrip_preservesEveryDueSnakeIdAndName_plusTheNextInstant() {
        val plan = ReminderPlanner.plan(candidates, now, zone)

        val decoded = decodeDuePayload(encodeDuePayload(freezeDuePayload(plan, candidates, zone)))

        assertEquals(
            listOf(FrozenDueSnake(1, "Noodle"), FrozenDueSnake(3, "Coil")),
            decoded.dueSnakes
        )
        assertEquals(Instant.parse("2026-09-15T07:00:00Z"), decoded.nextAlarmAt)
    }

    @Test
    fun emptyPlan_encodesAndDecodesToNoSnakes_andNoNextInstant() {
        val decoded = decodeDuePayload(
            encodeDuePayload(freezeDuePayload(ReminderPlan(emptySet(), null), emptyList(), zone))
        )

        assertEquals(emptyList<FrozenDueSnake>(), decoded.dueSnakes)
        assertEquals(null, decoded.nextAlarmAt)
    }

    @Test
    fun missingExtras_decodeToNoSnakesAndNoNextInstant() {
        // A bare intent (no frozen payload) must still decode cleanly so the
        // receiver can post nothing and re-arm (issue #37 WB4).
        assertEquals(FrozenDuePayload(emptyList(), null), decodeDuePayload(emptyMap()))
    }

    @Test
    fun partialExtras_decodeTheEntriesThatArePresent() {
        // A truncated Bundle: the count says two snakes but only index 0 made
        // it in. The present entry still decodes, the missing one simply
        // drops, and the next instant still reads (issue #37 WB4).
        val extras = mapOf(
            DUE_SNAKE_COUNT_KEY to "2",
            "${DUE_SNAKE_KEY_PREFIX}0_id" to "1",
            "${DUE_SNAKE_KEY_PREFIX}0_name" to "Noodle",
            NEXT_ALARM_AT_KEY to "1789455600000" // 2026-09-15T07:00:00Z
        )

        val decoded = decodeDuePayload(extras)

        assertEquals(listOf(FrozenDueSnake(1, "Noodle")), decoded.dueSnakes)
        assertEquals(Instant.parse("2026-09-15T07:00:00Z"), decoded.nextAlarmAt)
    }

    @Test
    fun nextInstantDecodes_withoutAnySnakes_whenOnlyTheInstantWasFrozen() {
        val extras = encodeDuePayload(
            FrozenDuePayload(emptyList(), Instant.parse("2026-09-16T07:00:00Z"))
        )

        assertEquals(FrozenDuePayload(emptyList(), Instant.parse("2026-09-16T07:00:00Z")), decodeDuePayload(extras))
    }
}
