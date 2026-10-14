package com.snaketracker.app.reminders

import com.snaketracker.app.data.dao.LastFeedingInfo
import com.snaketracker.app.data.entities.Snake
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The fix for the notification spam seen in the on-device debug log: a snake
 * that only becomes due at the armed instant was posted on every launch, boot,
 * clock change and data change, and the alarm's own post was repeated by the
 * launch pass. Now only snakes due *now* are posted, once per reminder day.
 */
class DueNotifierTest {

    private val zone: ZoneId = ZoneId.of("GMT+02:00")

    private fun epochMillisOf(localDateTime: String): Long =
        ZonedDateTime.of(LocalDateTime.parse(localDateTime), zone).toInstant().toEpochMilli()

    // ---- occurrence day: the local date of the most recent 09:00 ---------

    @Test
    fun occurrenceDay_beforeNineLocal_belongsToThePreviousReminderDay() {
        val at = Instant.parse("2026-09-14T06:59:59Z") // 08:59:59 local
        assertEquals(LocalDate.of(2026, 9, 13), ReminderPlanner.occurrenceDayFor(at, zone))
    }

    @Test
    fun occurrenceDay_atAndAfterNineLocal_belongsToTheSameDay() {
        assertEquals(
            LocalDate.of(2026, 9, 14),
            ReminderPlanner.occurrenceDayFor(Instant.parse("2026-09-14T07:00:00Z"), zone)
        )
        assertEquals(
            LocalDate.of(2026, 9, 14),
            ReminderPlanner.occurrenceDayFor(Instant.parse("2026-09-14T21:59:59Z"), zone) // 23:59:59 local
        )
    }

    // ---- ledger: once per snake per reminder day -------------------------

    private fun ledger(): DueLedger {
        val store = mutableMapOf<Long, Long>()
        return DueLedger(lastClaimedDay = { store[it] }, record = { id, day -> store[id] = day })
    }

    @Test
    fun ledger_claimsOncePerSnakePerDay() {
        val ledger = ledger()
        val day = LocalDate.of(2026, 9, 14)

        assertTrue(ledger.claim(1, day))
        assertFalse(ledger.claim(1, day)) // the launch pass right after the alarm
        assertTrue(ledger.claim(2, day))  // another snake is independent
    }

    @Test
    fun ledger_claimsAgainOnTheNextReminderDay_butNeverForAnEarlierOne() {
        val ledger = ledger()

        assertTrue(ledger.claim(1, LocalDate.of(2026, 9, 14)))
        assertTrue(ledger.claim(1, LocalDate.of(2026, 9, 15))) // the daily re-reminder
        assertFalse(ledger.claim(1, LocalDate.of(2026, 9, 14))) // clock moved back: no re-post
    }

    // ---- the launch pass posts only what is due NOW -----------------------

    @Test
    fun snapshot_ofASnakeDueOnlyAtTheArmedInstant_hasNothingDueNow() = runBlocking {
        // The debug-log case: fed, due tomorrow 09:00, armed for that instant.
        val now = Instant.parse("2026-09-14T10:00:00Z") // 12:00 local
        val snapshots = armingSnapshotFlow(
            snakes = flow { emit(listOf(Snake(id = 1, name = "Adora", feedingIntervalDays = 3))) },
            lastFeedings = flow {
                emit(listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00"))))
            },
            now = { now },
            zone = zone
        ).toList()

        val snapshot = snapshots.single()
        // The alarm still carries her (she is due at the armed instant)...
        assertEquals(listOf(FrozenDueSnake(1, "Adora")), snapshot.payload.dueSnakes)
        assertEquals(Instant.parse("2026-09-15T07:00:00Z"), snapshot.payload.nextAlarmAt)
        // ...but she is not due now, so nothing may be posted for her today.
        assertEquals(emptyList<FrozenDueSnake>(), snapshot.dueNowSnakes)
    }

    @Test
    fun snapshot_ofAnOverdueSnake_isDueNow() = runBlocking {
        val now = Instant.parse("2026-09-14T10:00:00Z")
        val snapshot = armingSnapshotFlow(
            snakes = flow { emit(listOf(Snake(id = 1, name = "Adora", feedingIntervalDays = 3))) },
            lastFeedings = flow {
                emit(listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-01T20:00"))))
            },
            now = { now },
            zone = zone
        ).toList().single()

        assertEquals(listOf(FrozenDueSnake(1, "Adora")), snapshot.dueNowSnakes)
    }
}
