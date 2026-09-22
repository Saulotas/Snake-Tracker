package com.snaketracker.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Instrumentation.ActivityMonitor
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snaketracker.app.reminders.NotificationHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #38: the notification helper's contract against the real
 * [NotificationManager] — one heads-up (high-importance) channel, a
 * tap-to-open content intent on every posted notification, and the per-id
 * replace-not-stack rule.
 *
 * The per-id cases are table-driven: a notification id added later only adds
 * a row to [PER_ID_CASES]. The [uiSuppressionChain] supplies POST_NOTIFICATIONS
 * up front so the helper's permission-gated post path runs (the same rule the
 * other instrumented tests compose).
 */
@RunWith(AndroidJUnit4::class)
class FeedingNotificationContractTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = uiSuppressionChain.around(composeRule)

    /** One row per notification id the helper uses: id + the snake name it posts. */
    private data class PerIdCase(val id: Long, val snakeName: String)

    private companion object {
        val PER_ID_CASES = listOf(
            PerIdCase(id = 1L, snakeName = "Nagini"),
            PerIdCase(id = 2L, snakeName = "Kaa"),
        )

        /** Scratch id for the raw-manager merge-rule pin. */
        const val MERGE_RULE_ID = "merge-rule-scratch"
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    // The Long->Int id conversion lives here once; the notification ids are
    // the snake ids (see NotificationHelper.showFeedingDueNotification).
    private fun has(id: Long) =
        manager.activeNotifications.any { it.id == id.toInt() }

    private fun countFor(id: Long) =
        manager.activeNotifications.count { it.id == id.toInt() }

    private fun postedFor(id: Long): Notification? =
        manager.activeNotifications.firstOrNull { it.id == id.toInt() }?.notification

    /**
     * Resolves a tap the way the system does: send the PendingIntent and
     * observe the launch through a class-filtered ActivityMonitor
     * (PendingIntent exposes no public Intent accessor on the compiled SDK).
     * The PI launch is dispatched on the main thread; the Compose rule's
     * waitUntil pumps that queue while the monitor is polled.
     */
    private fun launchVia(contentIntent: PendingIntent?): Activity? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityMonitor(MainActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        try {
            contentIntent?.send()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                monitor.lastActivity != null
            }
            return monitor.lastActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @After
    fun clearNotifications() {
        PER_ID_CASES.forEach { manager.cancel(it.id.toInt()) }
    }

    /** WB1: the single channel is created (once, at application start) with high importance. */
    @Test
    fun singleChannel_isCreatedWithHighImportance() {
        // Application.onCreate has already run, so one assert pins existence
        // and importance together: a missing channel yields a null importance.
        assertEquals(
            "the single channel must exist with heads-up (high) importance",
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)?.importance
        )
    }

    /**
     * In-place upgrade: the helper gives the HIGH-importance contract a fresh
     * channel id, so a legacy DEFAULT record (which the platform cannot raise
     * in place - see [existingChannel_importanceFollowsTheDocumentedMergeRule])
     * is replaced by a full HIGH record and the old id is retired.
     */
    @Test
    fun createChannel_upgradesImportanceWhenExistingChannelDiffers() {
        manager.deleteNotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID)
        // Simulate the record an older app version left behind.
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.LEGACY_CHANNEL_ID,
                "legacy",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        NotificationHelper.createChannel(context)

        assertEquals(
            "the active channel must be at heads-up (high) importance after an upgrade",
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)?.importance
        )
        assertEquals(
            "the retired legacy record must no longer resolve",
            null,
            manager.getNotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID)
        )
    }

    /**
     * The documented merge rule on an existing channel id: only a *lower*
     * importance is applied in place; a raise is ignored. The value travels
     * down only - which is why the helper's HIGH contract ships as a fresh id.
     */
    @Test
    fun existingChannel_importanceFollowsTheDocumentedMergeRule() {
        manager.deleteNotificationChannel(MERGE_RULE_ID)
        manager.createNotificationChannel(
            NotificationChannel(MERGE_RULE_ID, "merge", NotificationManager.IMPORTANCE_HIGH)
        )
        val afterFresh = manager.getNotificationChannel(MERGE_RULE_ID)?.importance
        assertNotNull("a fresh record takes its constructor importance", afterFresh)

        manager.createNotificationChannel(
            NotificationChannel(MERGE_RULE_ID, "merge", NotificationManager.IMPORTANCE_LOW)
        )
        val afterLower = manager.getNotificationChannel(MERGE_RULE_ID)?.importance
        assertEquals("a lower importance is applied to the existing record",
            NotificationManager.IMPORTANCE_LOW, afterLower)
        assertTrue("the merge only lowers the stored importance",
            (afterFresh ?: 0) >= (afterLower ?: 0))

        manager.createNotificationChannel(
            NotificationChannel(MERGE_RULE_ID, "merge", NotificationManager.IMPORTANCE_HIGH)
        )
        assertEquals("a raised importance is ignored on the existing record",
            afterLower, manager.getNotificationChannel(MERGE_RULE_ID)?.importance)

        manager.deleteNotificationChannel(MERGE_RULE_ID)
    }

    /** WB2: a posted feeding-due notification carries a tap intent to the launch activity. */
    @Test
    fun feedingDueNotification_exposesContentIntentToLaunchActivity_andAutoCancels() {
        assertEquals(
            "POST_NOTIFICATIONS must be granted for the post path",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        )
        for ((id, snakeName) in PER_ID_CASES) {
            NotificationHelper.showFeedingDueNotification(context, id, snakeName)

            // The active-notifications list reflects asynchronously after
            // notify(); pump through the Compose rule's waitUntil.
            composeRule.waitUntil(timeoutMillis = 5_000) { has(id) }
            val posted = postedFor(id)
            assertNotNull("id $id must be active after posting", posted)

            val launched = checkNotNull(launchVia(posted?.contentIntent)) {
                "the content intent must launch an activity"
            }
            assertEquals(
                "the tap must open the launch activity",
                MainActivity::class.java.name,
                launched.intent.component?.className
            )

            assertEquals(
                "id $id keeps auto-cancel so the tap clears the notification",
                Notification.FLAG_AUTO_CANCEL,
                posted!!.flags and Notification.FLAG_AUTO_CANCEL
            )
            // The copy is string-resource driven and names the snake.
            assertEquals(
                context.getString(R.string.notification_feeding_due_title, snakeName),
                posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            )
        }
    }

    /**
     * WB2 addendum - PI-level behavior of the tap: a raw
     * `PendingIntent.send()` dispatches the launch intent, but the shade
     * row's auto-cancel belongs to the SystemUI click callback, so the row
     * itself stays until the next post. Together with the
     * [Notification.FLAG_AUTO_CANCEL] bit assert in
     * [feedingDueNotification_exposesContentIntentToLaunchActivity_andAutoCancels]
     * this pins both halves of "the tap clears the notification": the flag
     * the builder sets and the dispatch the flag drives. (The managed
     * aosp-atd image's a11y tree exposes only the app window, so the shade
     * row itself is not clickable in this harness.)
     */
    @Test
    fun contentIntentSend_dispatchesTheLaunch_whileTheRowStaysUntilTheUiClick() {
        val (id, snakeName) = PER_ID_CASES.first()
        NotificationHelper.showFeedingDueNotification(context, id, snakeName)
        composeRule.waitUntil(timeoutMillis = 5_000) { has(id) }

        val launched = checkNotNull(launchVia(postedFor(id)?.contentIntent)) {
            "the content intent must launch an activity"
        }
        assertEquals(
            "the tap must open the launch activity",
            MainActivity::class.java.name,
            launched.intent.component?.className
        )

        // The PI dispatch is not the shade click: the row remains until the
        // next post to the same id replaces it (the notify-by-id rule).
        assertEquals("the row survives the raw PI send", 1, countFor(id))
    }

    /** WB3: snakes due at one instant keep their own ids — one notification per id. */
    @Test
    fun twoSnakesDueAtOneInstant_yieldOneNotificationPerId() {
        for ((id, snakeName) in PER_ID_CASES) {
            NotificationHelper.showFeedingDueNotification(context, id, snakeName)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            PER_ID_CASES.all { has(it.id) }
        }
        val postedIds = manager.activeNotifications
            .filter { entry -> PER_ID_CASES.any { it.id.toInt() == entry.id } }
            .map { it.id }
            .sorted()
        assertEquals(
            "each due snake keeps its own notification id",
            PER_ID_CASES.map { it.id.toInt() }.sorted(),
            postedIds
        )
    }

    /** WB3: re-posting one id replaces that id's notification instead of stacking. */
    @Test
    fun postingTwiceForOneId_replacesItsOwnNotification() {
        for ((id, snakeName) in PER_ID_CASES) {
            NotificationHelper.showFeedingDueNotification(context, id, snakeName)
            NotificationHelper.showFeedingDueNotification(context, id, snakeName)

            composeRule.waitUntil(timeoutMillis = 5_000) { countFor(id) == 1 }
            assertEquals("re-posting id $id must replace, not stack", 1, countFor(id))
        }
    }
}
