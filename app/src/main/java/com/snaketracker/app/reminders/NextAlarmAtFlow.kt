package com.snaketracker.app.reminders

import com.snaketracker.app.data.buildReminderCandidates
import com.snaketracker.app.data.dao.LastFeedingInfo
import com.snaketracker.app.data.entities.Snake
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId

/**
 * The reminder-arming stream: observes the existing snakes and last-feeding-per-snake
 * flows and emits the due-time engine's frozen payload — the due-now snakes plus
 * the single next alarm instant, so every armed alarm carries its payload in the
 * intent extras (issue #37 WB1). Consecutive duplicates are suppressed, so a data
 * change only re-arms when the frozen payload actually changes.
 */
internal fun nextAlarmAtFlow(
    snakes: Flow<List<Snake>>,
    lastFeedings: Flow<List<LastFeedingInfo>>,
    now: () -> Instant,
    zone: ZoneId
): Flow<FrozenDuePayload> =
    combine(snakes, lastFeedings) { snakeList, feedingList ->
        val candidates = buildReminderCandidates(snakeList.filter { it.remindersEnabled }, feedingList)
        freezeDuePayload(
            plan = ReminderPlanner.plan(candidates = candidates, now = now(), zone = zone),
            candidates = candidates,
            zone = zone
        )
    }.distinctUntilChanged()

/**
 * The launch-time observer's pass (issue #40 WB1/WB2): collects the stream and
 * runs the full snapshot→notify→re-arm sequence once per distinct frozen
 * payload, so a cold start posts the already-due snakes immediately instead of
 * waiting for the next due hour, and the alarm is armed for the following
 * instant. The stream's [distinctUntilChanged] dedupes upstream, so a data
 * change that leaves the payload identical runs no second pass. Every caller
 * passes a non-null [notify]; the notify-free reschedule-only shape is owned
 * by [ReminderArming.reschedule] (issue #28 WB5).
 */
internal suspend fun launchArmingPass(
    payloadFlow: Flow<FrozenDuePayload>,
    notify: (FrozenDueSnake) -> Unit,
    reschedule: suspend (FrozenDuePayload) -> Unit
) {
    payloadFlow.collect { frozen ->
        notifyFrozenPayloadAndReschedule(
            payload = frozen,
            notify = notify,
            reschedule = { reschedule(frozen) }
        )
    }
}
