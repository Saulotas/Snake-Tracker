package com.snaketracker.app.reminders

import android.content.Intent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #39: a manual clock edit ([Intent.ACTION_TIME_CHANGED]) or a zone
 * switch ([Intent.ACTION_TIMEZONE_CHANGED]) must push the single armed instant
 * through the same plan→notify→re-arm funnel the boot and permission receivers
 * use, so the reminder follows the new local time. [handleClockChangeIntent]
 * is the receiver's pure action-guard half: one matched action calls the
 * shared refresh exactly once, anything else is ignored.
 */
class ClockChangeReceiverTest {

    @Test
    fun timeChanged_callsTheSharedRefresh_exactlyOnce() {
        var refreshCalls = 0
        handleClockChangeIntent(Intent.ACTION_TIME_CHANGED) { refreshCalls += 1 }
        assertEquals(1, refreshCalls)
    }

    @Test
    fun timezoneChanged_callsTheSharedRefresh_exactlyOnce() {
        var refreshCalls = 0
        handleClockChangeIntent(Intent.ACTION_TIMEZONE_CHANGED) { refreshCalls += 1 }
        assertEquals(1, refreshCalls)
    }

    /**
     * The guard compares the constants with themselves, so only a read of the
     * manifest can pin the wiring: an `<action>` literal must equal the value
     * the framework actually broadcasts (`Intent.ACTION_TIME_CHANGED` is
     * `"android.intent.action.TIME_SET"`), or the receiver never wakes.
     */
    @Test
    fun manifestActionNames_matchTheFrameworkConstants() {
        val manifest = listOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        ).map(::File).firstOrNull(File::exists)
            ?: error("AndroidManifest.xml not found from the unit-test working directory")

        val declaredActions = Regex("""<action android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toList()

        assertTrue("TIME_SET broadcast missing from $declaredActions", declaredActions.contains(Intent.ACTION_TIME_CHANGED))
        assertTrue(
            "TIMEZONE_CHANGED broadcast missing from $declaredActions",
            declaredActions.contains(Intent.ACTION_TIMEZONE_CHANGED),
        )
    }

    @Test
    fun unrelatedAction_isIgnored_andNeverReachesTheRefresh() {
        var refreshCalls = 0
        handleClockChangeIntent(Intent.ACTION_EDIT) { refreshCalls += 1 }
        assertEquals(0, refreshCalls)
    }

    @Test
    fun nullAction_isIgnored() {
        var refreshCalls = 0
        handleClockChangeIntent(null) { refreshCalls += 1 }
        assertEquals(0, refreshCalls)
    }
}
