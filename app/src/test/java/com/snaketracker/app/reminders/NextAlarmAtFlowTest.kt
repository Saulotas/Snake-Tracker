package com.snaketracker.app.reminders

import com.snaketracker.app.data.dao.LastFeedingInfo
import com.snaketracker.app.data.entities.Snake
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NextAlarmAtFlowTest {

    // Fixed +02:00 zone so every expectation is a hand-computed literal.
    private val zone: ZoneId = ZoneId.of("GMT+02:00")

    // 2026-09-14 12:00 local. Today's 09:00 local has passed; the next 09:00
    // local is 2026-09-15T07:00:00Z.
    private val now: Instant = Instant.parse("2026-09-14T10:00:00Z")

    // Epoch millis for a local wall-clock time in the fixed zone.
    private fun epochMillisOf(localDateTime: String): Long =
        ZonedDateTime.of(LocalDateTime.parse(localDateTime), zone).toInstant().toEpochMilli()

    private val snake = Snake(id = 1, name = "Noodle", feedingIntervalDays = 3)

    @Test
    fun emitsNextDueInstantImmediately_soAppStartArmsTheAlarm() = runBlocking {
        // Fed 2026-09-12 20:00 local, interval 3 -> due 2026-09-15 09:00 local.
        val feedings = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)) },
            lastFeedings = flow { emit(feedings) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            // Noodle becomes due exactly at the armed instant, so the frozen
            // payload carries it beside that instant (issue #37 F1).
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                )
            ),
            emissions
        )
    }

    @Test
    fun reEmits_whenLoggingAFeedingMovesTheNextDueInstant() = runBlocking {
        val beforeFeeding = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))
        val afterFeeding = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-13T20:00")))

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)) },
            lastFeedings = flow { emit(beforeFeeding); emit(afterFeeding) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                ),
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-16T07:00:00Z")
                )
            ),
            emissions
        )
    }

    @Test
    fun suppressesReEmission_whenDataChangesButTheComputedInstantDoesNot() = runBlocking {
        // Adding an older feeding row leaves the paired last-feeding instant
        // (and the frozen payload) untouched: the change must not re-arm.
        val latestOnly = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))
        val latestPlusOlder = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-01T20:00"))) + latestOnly

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)) },
            lastFeedings = flow { emit(latestOnly); emit(latestPlusOlder) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                )
            ),
            emissions
        )
    }

    @Test
    fun emissionCarriesTheFrozenDueSnakes_soEveryArmedAlarmFreezesItsPayload() = runBlocking {
        // Never-fed snake: due immediately, so the emission's frozen payload
        // carries its id and name next to the next instant (issue #37 WB1).
        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)) },
            lastFeedings = flow { emit(emptyList<LastFeedingInfo>()) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                )
            ),
            emissions
        )
    }

    @Test
    fun emitsEmptyPayload_whenThereAreNoReminderEnabledSnakes_soEverythingIsCancelled() = runBlocking {
        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(emptyList<Snake>()) },
            lastFeedings = flow { emit(emptyList<LastFeedingInfo>()) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(listOf(FrozenDuePayload(emptyList(), null)), emissions)
    }

    @Test
    fun ignoresSnakes_whoseRemindersAreDisabled() = runBlocking {
        val disabled = snake.copy(remindersEnabled = false)

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(disabled)) },
            lastFeedings = flow { emit(listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(listOf(FrozenDuePayload(emptyList(), null)), emissions)
    }

    @Test
    fun reEmits_whenRemindersAreToggledBackOn() = runBlocking {
        val feedings = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake.copy(remindersEnabled = false))); emit(listOf(snake)) },
            lastFeedings = flow { emit(feedings) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            listOf(
                FrozenDuePayload(emptyList(), null),
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                )
            ),
            emissions
        )
    }

    @Test
    fun emitsNull_whenRemindersAreToggledOff_soTheArmedAlarmIsCancelled() = runBlocking {
        val feedings = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)); emit(listOf(snake.copy(remindersEnabled = false))) },
            lastFeedings = flow { emit(feedings) },
            now = { now },
            zone = zone
        ).toList()

        // Armed -> cancelled: the second emission must carry no next instant so
        // the receiver side cancels the previously armed alarm instead of
        // leaving it stale.
        assertEquals(
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                ),
                FrozenDuePayload(emptyList(), null)
            ),
            emissions
        )
    }

    @Test
    fun renameReArms_soTheFrozenTextStaysFresh() = runBlocking {
        // A name-only change leaves the computed instant untouched, but the
        // dedupe compares the whole frozen payload, so the renamed snake
        // re-arms and the extras never carry the stale name (issue #37).
        val feedings = listOf(LastFeedingInfo(snakeId = 1, lastDate = epochMillisOf("2026-09-12T20:00")))

        val emissions = nextAlarmAtFlow(
            snakes = flow { emit(listOf(snake)); emit(listOf(snake.copy(name = "Noodle II"))) },
            lastFeedings = flow { emit(feedings) },
            now = { now },
            zone = zone
        ).toList()

        assertEquals(
            listOf(
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                ),
                FrozenDuePayload(
                    dueSnakes = listOf(FrozenDueSnake(1, "Noodle II")),
                    nextAlarmAt = Instant.parse("2026-09-15T07:00:00Z")
                )
            ),
            emissions
        )
    }
}
