package com.snaketracker.app.reminders

import android.content.Context
import com.snaketracker.app.data.AppDatabase
import com.snaketracker.app.data.Repository
import java.time.Instant
import java.time.ZoneId

/**
 * The one frozen-payload→notify→re-arm sequence behind every entry that
 * notifies (platform seams as lambdas, JVM-testable like the arm ladder in
 * ReminderScheduler): posts one notification per frozen snake straight from
 * the payload — no database read in the notification path — and only then
 * runs the re-arm [reschedule] (the fresh snapshot→plan pass that arms the
 * next instant or cancels when there is none; issue #37 WB2/WB3). An empty
 * payload posts nothing and still re-arms (issue #37 WB4). The instant moves
 * inside [FrozenDuePayload.nextAlarmAt], so it is the single source for it.
 */
internal suspend fun notifyFrozenPayloadAndReschedule(
    payload: FrozenDuePayload,
    notify: (FrozenDueSnake) -> Unit,
    reschedule: suspend () -> Unit
) {
    for (snake in payload.dueSnakes) {
        notify(snake)
    }
    reschedule()
}

/**
 * The two arming entries over the one shared sequence: [refresh] runs the full
 * snapshot→freeze→notify→re-arm pass and is used by [AlarmReceiver] (after its
 * post from the decoded extras), [BootReceiver] and
 * [ExactAlarmPermissionReceiver] (the system already cancels the alarm on
 * permission revoke); [reschedule] is the notify-free counterpart for bulk
 * data changes such as a backup import, where replacing the data is not a
 * due-time event so it must not burst notifications (issue #28 WB5) - the
 * caller runs it off the main thread. Both freeze one payload per arming from
 * their own fresh plan (issue #37 WB1), so the armed alarm always carries the
 * snakes due at its very instant.
 */
object ReminderArming {
    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val frozen = snapshotAndFreeze(appContext)
        notifyFrozenPayloadAndReschedule(
            payload = frozen,
            notify = { snake ->
                NotificationHelper.showFeedingDueNotification(
                    appContext, snake.snakeId, snake.name
                )
            },
            reschedule = { ReminderScheduler.reschedule(appContext, frozen) }
        )
    }

    /**
     * The reschedule-only entry: recomputes and freezes the plan from the
     * current data and hands the frozen payload straight to
     * [ReminderScheduler.reschedule] - no [refresh] notify pass by
     * construction (issue #28 WB5).
     */
    suspend fun reschedule(context: Context) {
        val appContext = context.applicationContext
        ReminderScheduler.reschedule(appContext, snapshotAndFreeze(appContext))
    }

    /**
     * The one snapshot→plan→freeze setup both entries consume: the shared
     * Repository's current data, planned against the device clock and zone,
     * frozen into the payload its armed instant will carry. Stated once so a
     * clock/zone/snapshot change is a single edit (PR #34 review 🟡); only the
     * notify-vs-reschedule difference stays at the entries.
     */
    private suspend fun snapshotAndFreeze(appContext: Context): FrozenDuePayload {
        val zone = ZoneId.systemDefault()
        val candidates = Repository.getInstance(AppDatabase.getInstance(appContext))
            .getReminderSnapshot()
        val plan = ReminderPlanner.plan(
            candidates = candidates,
            now = Instant.now(),
            zone = zone
        )
        return freezeDuePayload(plan = plan, candidates = candidates, zone = zone)
    }
}
