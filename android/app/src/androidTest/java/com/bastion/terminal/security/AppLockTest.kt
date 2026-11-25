package com.bastion.terminal.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith


/**
 * PIN checks on a real device (PBKDF2, SharedPreferences). The wipe itself is
 * NOT executed here — this runs inside the installed app and would erase the
 * emulator's real library. Its file list is checked instead.
 */
@RunWith(AndroidJUnit4::class)
class AppLockTest {
    @Test
    fun pinsAreHashedCheckedAndDistinct() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        try {
            prefs.edit().clear().commit()
            assertFalse(AppLock.hasAppPin(ctx))
            assertTrue(AppLock.validPin("4821") && !AppLock.validPin("12") && !AppLock.validPin("12ab"))

            AppLock.setAppPin(ctx, "4821")
            assertTrue(AppLock.hasAppPin(ctx))
            assertTrue(AppLock.checkAppPin(ctx, "4821"))
            assertFalse(AppLock.checkAppPin(ctx, "4822"))
            // The PIN itself is never stored.
            assertFalse(prefs.all.values.any { it.toString().contains("4821") })

            // The wipe PIN must differ from the app PIN.
            assertFalse(AppLock.setWipePin(ctx, "4821"))
            assertTrue(AppLock.setWipePin(ctx, "9900"))
            assertTrue(AppLock.isWipePin(ctx, "9900"))
            assertFalse(AppLock.isWipePin(ctx, "4821"))

            // Wrong PINs: refused, and locked out after five.
            repeat(5) { assertEquals(AppLock.Result.WRONG, AppLock.submit(ctx, "0000", now = 1_000L)) }
            assertEquals(AppLock.Result.LOCKED_OUT, AppLock.submit(ctx, "4821", now = 2_000L))
            assertEquals(AppLock.Result.OPEN, AppLock.submit(ctx, "4821", now = 1_000L + 31_000L))

            // Removing the wipe PIN keeps the app PIN; the old wipe PIN is then just a wrong PIN.
            AppLock.clearWipePin(ctx)
            assertFalse(AppLock.hasWipePin(ctx))
            assertTrue(AppLock.checkAppPin(ctx, "4821"))
            assertEquals(AppLock.Result.WRONG, AppLock.submit(ctx, "9900", now = 100_000_000L))
        } finally {
            prefs.edit().clear().commit()
            prefs.edit().apply { saved.forEach { (k, v) -> if (v is String) putString(k, v) } }.commit()
        }
    }

    @Test
    fun wipeKeepsOnlyTheModelAndTheLock() {
        // Everything is wiped except this short keep-list.
        assertEquals(setOf("models"), AppLock.KEEP_FILE_DIRS)
        assertEquals(setOf("lock", "local_ai"), AppLock.KEEP_PREFS)
    }
}
