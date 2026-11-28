package com.bastion.terminal.hw

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceFactsInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test
    fun checksFactsAndEmitsAnOfflineReport() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val facts = withContext(Dispatchers.IO) { DeviceCheck.readFacts(context) }
        assertEquals(Build.MANUFACTURER, facts.maker)
        assertEquals(Build.MODEL, facts.model)
        assertEquals(Build.VERSION.SDK_INT, facts.sdk)
        assertEquals(Build.SUPPORTED_ABIS.toList(), facts.abis)
        assertTrue("Android reports positive kernel-visible RAM", facts.totalRamBytes > 0)
        assertTrue("Phone filesystem free space is readable", (facts.phoneFreeBytes ?: -1L) >= 0L)
        assertEquals(facts.emulator, facts.deviceLabel.endsWith("emulator — not a phone"))
        val verdict = DeviceCheck.evaluate(facts)
        assertEquals(DeviceCheck.REQUIRED_ABI in facts.abis, verdict.requirements.single { it.name == "64-bit ARM" }.met)
        assertTrue(verdict.tier == "Expected" || verdict.tier == "Not supported")

        val report = DeviceReport(facts, verdict)
        val folder = File(checkNotNull(context.externalMediaDirs.firstOrNull()), "device-check")
        val json = File(folder, "device-check.json")
        val summary = File(folder, "device-check.txt")
        val saved = withContext(Dispatchers.IO) {
            if (!json.exists() && !summary.exists()) {
                DeviceReportStore.save(context, report, onlyIfNew = true)
                assertEquals(1, JSONObject(json.readText()).getInt("schemaVersion"))
                assertTrue("Saved summary includes device label", facts.deviceLabel in summary.readText())
                true
            } else false
        }
        // Emit the fresh result even when the device already has a report to preserve.
        instrumentation.sendStatus(2, Bundle().apply {
            putString("deviceCheckJson", report.toJson().toString())
            putString("deviceCheckSummary", report.summary())
            putBoolean("deviceCheckSaved", saved)
        })
    }
}
