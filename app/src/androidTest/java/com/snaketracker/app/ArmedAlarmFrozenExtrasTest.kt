package com.snaketracker.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snaketracker.app.reminders.AlarmReceiver
import com.snaketracker.app.reminders.FrozenDuePayload
import com.snaketracker.app.reminders.FrozenDueSnake
import com.snaketracker.app.reminders.ReminderScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Instrumented companion to the JVM payload tests: takes the armed alarm's own
 * `PendingIntent` back out of the framework and dispatches it, so the real
 * Bundle round-trip in front of `decodeDuePayload` and the
 * `FLAG_UPDATE_CURRENT` replacement on re-arm are covered on a device — the
 * pieces no JVM test can reach (issue #37).
 *
 * The observation point is the posted notification: the fire path posts one
 * row per frozen snake under that snake's own id, so the shade shows exactly
 * which payload the armed alarm carried.
 */
@RunWith(AndroidJUnit4::class)
class ArmedAlarmFrozenExtrasTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = uiSuppressionChain.around(composeRule)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** WB1 + WB2: the armed alarm carries the frozen payload, and dispatching
     *  it posts the due snake without any database read. */
    @Test
    fun armedAlarm_carriesTheFrozenPayload_andDispatchPostsIt() {
        ReminderScheduler.reschedule(context, firstPayload)

        val armed = checkNotNull(armedAlarm()) { "the alarm must be armed after reschedule" }
        armed.send()

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { titleFor(SNAKE_NOODLE) != null }
        assertEquals(
            "the armed alarm must post the snake frozen into its extras",
            dueTitle("Noodle"),
            titleFor(SNAKE_NOODLE)
        )
    }

    /** WB3 on the re-arm edge: the one alarm record is replaced in place, so a
     *  dispatch after re-arming posts the new payload under each snake's id. */
    @Test
    fun reArmedAlarm_replacesTheFrozenPayload_inTheSameRecord() {
        ReminderScheduler.reschedule(context, firstPayload)
        checkNotNull(armedAlarm()) { "the alarm must be armed after reschedule" }.send()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) { titleFor(SNAKE_NOODLE) != null }

        ReminderScheduler.reschedule(context, secondPayload)
        val rearmed = checkNotNull(armedAlarm()) { "the re-arm must keep the one alarm record" }
        rearmed.send()

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            titleFor(SNAKE_NOODLE) == dueTitle("Noodle II") && titleFor(SNAKE_COIL) != null
        }
        assertEquals(
            "the re-armed record must carry the renamed snake",
            dueTitle("Noodle II"),
            titleFor(SNAKE_NOODLE)
        )
        assertEquals(
            "the re-armed record must carry every snake of the new payload",
            dueTitle("Coil"),
            titleFor(SNAKE_COIL)
        )
    }

    /** The one armed alarm, looked up by its request code without creating one
     *  (FLAG_NO_CREATE). S+ requires the mutability flag on every call, this
     *  lookup included, so it mirrors the arming side's FLAG_IMMUTABLE. */
    private fun armedAlarm(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        ReminderScheduler.ALARM_REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FEEDING_DUE),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    private fun titleFor(snakeId: Long): String? = manager.activeNotifications
        .firstOrNull { it.id == snakeId.toInt() }
        ?.notification
        ?.extras
        ?.getCharSequence(Notification.EXTRA_TITLE)
        ?.toString()

    private fun dueTitle(snakeName: String): String =
        context.getString(R.string.notification_feeding_due_title, snakeName)

    @After
    fun clearState() {
        manager.cancel(SNAKE_NOODLE.toInt())
        manager.cancel(SNAKE_COIL.toInt())
        // No due snakes and no next instant: the single alarm is cancelled.
        ReminderScheduler.reschedule(context, FrozenDuePayload(emptyList(), null))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val SNAKE_NOODLE = 1L
        const val SNAKE_COIL = 2L

        val firstPayload = FrozenDuePayload(
            dueSnakes = listOf(FrozenDueSnake(SNAKE_NOODLE, "Noodle")),
            nextAlarmAt = Instant.parse("2099-01-01T07:00:00Z")
        )

        val secondPayload = FrozenDuePayload(
            dueSnakes = listOf(
                FrozenDueSnake(SNAKE_NOODLE, "Noodle II"),
                FrozenDueSnake(SNAKE_COIL, "Coil")
            ),
            nextAlarmAt = Instant.parse("2099-01-02T07:00:00Z")
        )
    }
}
