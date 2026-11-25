package com.bastion.terminal.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSubscriptionTest {
    /** Task 2, finding 11: a failed re-registration during a rate switch does not leave "listening" stuck on. */
    @Test fun failedRateSwitchLetsALaterStartRecover() {
        var fail = false; var requests = 0; var active = false
        val gps = GpsSubscription(
            request = { if (fail) throw SecurityException("permission withdrawn"); requests++; active = true },
            remove = { active = false },
        )
        assertTrue(gps.start()); assertTrue(active)
        fail = true
        gps.rate(fast = true)          // the old request is removed, the new one fails
        assertFalse(active)
        assertFalse(gps.listening)
        fail = false
        assertTrue(gps.start())        // recovers instead of returning early
        assertTrue(active)
        assertEquals(2, requests)
    }

    @Test fun rateSwitchKeepsListening() {
        var requests = 0
        val gps = GpsSubscription(request = { requests++ }, remove = {})
        gps.start(); gps.rate(fast = true); gps.rate(fast = false)
        assertTrue(gps.listening); assertEquals(3, requests)
        gps.stop(); assertFalse(gps.listening)
    }
}
