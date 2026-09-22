package com.snaketracker.app.reminders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderArmingTest {

    // Fixed +02:00 zone so every expectation is a hand-computed literal.
    private val zone: ZoneId = ZoneId.of("GMT+02:00")

    // 2026-09-14 12:00 local — today's 09:00 reminder has already passed.
    private val now: Instant = Instant.parse("2026-09-14T10:00:00Z")

    @Test
    fun dueNowSnakes_areNotified_onceEach_andNotDueOnesAreSkipped() = runBlocking {
        val candidates = listOf(
            // Fed 2026-09-01; interval 7 → due 2026-09-08 09:00 local, overdue now.
            ReminderCandidate(1, "Due", 7, Instant.parse("2026-09-01T12:00:00Z")),
            // Fed 2026-09-13 20:00 local; interval 7 → due 2026-09-20 09:00 local,
            // still after the armed instant 09:00 tomorrow → not notified.
            ReminderCandidate(2, "Future", 7, Instant.parse("2026-09-13T18:00:00Z"))
        )

        val frozen = freezeDuePayload(ReminderPlanner.plan(candidates, now, zone), candidates, zone)
        val notified = mutableListOf<Long>()

        notifyFrozenPayloadAndReschedule(
            payload = frozen,
            notify = { snake -> notified.add(snake.snakeId) },
            reschedule = { }
        )

        assertEquals(listOf(1L), notified)
    }

    /**
     * The one instant: the plan's next alarm travels inside the frozen payload
     * (S2) and the shared sequence runs the re-arm after every post, so the
     * alarm always follows the plan exactly (issue #37 WB3).
     */
    @Test
    fun payloadCarriesThePlanInstant_andTheSequenceReschedules_afterEveryPost() = runBlocking {
        val candidates = listOf(
            // Fed 2026-09-09 20:00 local; interval 7 → due 2026-09-16 09:00 local,
            // the very instant the alarm is armed for (issue #37 F1).
            ReminderCandidate(3, "Soon", 7, Instant.parse("2026-09-09T18:00:00Z"))
        )

        val frozen = freezeDuePayload(ReminderPlanner.plan(candidates, now, zone), candidates, zone)
        assertEquals(Instant.parse("2026-09-16T07:00:00Z"), frozen.nextAlarmAt)

        val calls = mutableListOf<String>()
        notifyFrozenPayloadAndReschedule(
            payload = frozen,
            notify = { snake -> calls.add("notify:${snake.snakeId}") },
            reschedule = { calls.add("reschedule") }
        )

        assertEquals(listOf("notify:3", "reschedule"), calls)
    }

    /**
     * The reschedule-only entry (#28 WB5): after a bulk data change such as a
     * backup import, the entry hands the frozen payload straight to
     * ReminderScheduler — the instant that moves the alarm is the payload's
     * own, and by construction this entry has no notify channel, unlike
     * [notifyFrozenPayloadAndReschedule].
     */
    @Test
    fun rescheduleOnlyPayload_carriesThePlanInstant_forTheScheduler_toMoveTheAlarm() {
        val candidates = listOf(
            // Fed 2026-09-01; interval 7 → due 2026-09-08 09:00 local, overdue now.
            ReminderCandidate(1, "Due", 7, Instant.parse("2026-09-01T12:00:00Z"))
        )

        val frozen = freezeDuePayload(ReminderPlanner.plan(candidates, now, zone), candidates, zone)

        // Overdue at 12:00 local with today's 09:00 already passed → next 09:00
        // local: the scheduler moves the alarm to the instant the payload carries.
        assertEquals(Instant.parse("2026-09-15T07:00:00Z"), frozen.nextAlarmAt)
    }

    @Test
    fun rescheduleOnlyPayload_withNoNextAlarm_carriesNull_soEverythingIsCancelled() {
        val frozen = freezeDuePayload(ReminderPlan(emptySet(), null), emptyList(), zone)

        assertEquals(null, frozen.nextAlarmAt)
    }

    /**
     * Issue #37 WB2/WB3: on alarm fire the notification path runs straight off
     * the frozen payload — one post per frozen snake, in order — and the
     * re-arm happens only after every post, so the shade fills before the
     * Room-backed reschedule starts.
     */
    @Test
    fun frozenPayload_notifiesEverySnake_thenReschedules_last() = runBlocking {
        val payload = FrozenDuePayload(
            dueSnakes = listOf(FrozenDueSnake(1, "Noodle"), FrozenDueSnake(3, "Coil")),
            nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
        )
        val calls = mutableListOf<String>()

        notifyFrozenPayloadAndReschedule(
            payload = payload,
            notify = { snake -> calls.add("notify:${snake.snakeId}:${snake.name}") },
            reschedule = { calls.add("reschedule") }
        )

        assertEquals(listOf("notify:1:Noodle", "notify:3:Coil", "reschedule"), calls)
    }

    /**
     * Issue #37 WB4: an extras payload with no snakes posts nothing and still
     * runs the re-arm, so a bare alarm can never stall the reminder chain.
     */
    @Test
    fun emptyFrozenPayload_postsNothing_andStillReschedules() = runBlocking {
        val calls = mutableListOf<String>()

        notifyFrozenPayloadAndReschedule(
            payload = FrozenDuePayload(emptyList(), null),
            notify = { calls.add("notify:${it.snakeId}") },
            reschedule = { calls.add("reschedule") }
        )

        assertEquals(listOf("reschedule"), calls)
    }
}
