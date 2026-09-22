package com.snaketracker.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulerTest {

    @Test
    fun beforeAndroid12_usesTheExactApi_withoutAnyPermission() {
        // SCHEDULE_EXACT_ALARM does not exist below API 31; the exact API is free.
        assertTrue(shouldUseExactApi(sdkInt = 26, exactPermissionHeld = false))
        assertTrue(shouldUseExactApi(sdkInt = 30, exactPermissionHeld = false))
    }

    @Test
    fun android12Plus_usesTheExactApi_whenThePermissionIsHeld() {
        assertTrue(shouldUseExactApi(sdkInt = 31, exactPermissionHeld = true))
        assertTrue(shouldUseExactApi(sdkInt = 34, exactPermissionHeld = true))
    }

    @Test
    fun android12Plus_fallsBackToTheBoundedWindow_whenThePermissionIsDenied() {
        assertFalse(shouldUseExactApi(sdkInt = 31, exactPermissionHeld = false))
        assertFalse(shouldUseExactApi(sdkInt = 34, exactPermissionHeld = false))
    }

    @Test
    fun armViaLadder_degradesToTheBoundedWindow_whenTheExactSetThrowsSecurityException() {
        val calls = mutableListOf<String>()

        armViaPermissionLadder(
            sdkInt = 34,
            exactPermissionHeld = true,
            setExact = { calls.add("exact"); throw SecurityException("permission revoked mid-flight") },
            setInexact = { calls.add("window") }
        )

        // The revocation race must degrade to the bounded-window fallback, not
        // crash: the reminder still lands inside the stated 10-minute window
        // on the correct day (issue #36 WB3).
        assertEquals(listOf("exact", "window"), calls)
    }

    @Test
    fun armViaLadder_stopsAtTheExactApi_whenTheExactSetSucceeds() {
        val calls = mutableListOf<String>()

        armViaPermissionLadder(
            sdkInt = 34,
            exactPermissionHeld = true,
            setExact = { calls.add("exact") },
            setInexact = { calls.add("window") }
        )

        assertEquals(listOf("exact"), calls)
    }

    @Test
    fun armViaLadder_skipsTheExactApi_onAndroid12PlusWhenThePermissionIsDenied() {
        val calls = mutableListOf<String>()

        armViaPermissionLadder(
            sdkInt = 31,
            exactPermissionHeld = false,
            setExact = { calls.add("exact") },
            setInexact = { calls.add("window") }
        )

        // Without the grant the same due instant is armed as the bounded
        // window call, never the exact one (issue #36 WB2).
        assertEquals(listOf("window"), calls)
    }

    @Test
    fun fallbackWindow_isTenMinutes() {
        // 10 minutes in millis — the stated bound the ungranted rung arms with.
        assertEquals(600_000L, FALLBACK_WINDOW_MILLIS)
    }
}
