package com.bastion.terminal.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCheckVerdictTest {
    private val passing = DeviceFacts(
        maker = "Sample",
        model = "Fixture phone",
        androidVersion = "10",
        sdk = 29,
        abis = listOf("arm64-v8a"),
        totalRamBytes = 8_000_000_000,
        advertisedRamBytes = null,
        phoneFreeBytes = 3_450_863_616,
        sdFreeBytes = emptyList(),
        knownNoSdSlot = false,
        gps = true,
        ble = true,
        openCl = false,
        deviceOwner = false,
        meshtasticInstalled = false,
        kiwixInstalled = false,
        atakInstalled = false,
        emulator = false,
        importedModelBytes = null,
    )

    @Test
    fun exactlyTheMinimumIsExpectedEvenBeforeCompanionSetup() {
        val verdict = DeviceCheck.evaluate(passing)
        assertEquals(6, verdict.requirements.size)
        assertTrue(verdict.meetsRequirements)
        assertEquals("Expected", verdict.tier)
        assertEquals("Meets requirements (Expected)", verdict.summary)
    }

    @Test
    fun eachRequirementCanFailIndependently() {
        val failures = listOf(
            "Android 10+" to passing.copy(sdk = 28),
            "64-bit ARM" to passing.copy(abis = listOf("x86_64", "armeabi-v7a")),
            "GPS" to passing.copy(gps = false),
            "Bluetooth LE" to passing.copy(ble = false),
            "Phone storage" to passing.copy(phoneFreeBytes = 3_450_863_615),
            "8 GB RAM" to passing.copy(totalRamBytes = 7_999_999_999),
        )
        for ((expected, data) in failures) {
            val verdict = DeviceCheck.evaluate(data)
            assertFalse(verdict.meetsRequirements)
            assertEquals("Not supported", verdict.tier)
            val failed = verdict.requirements.filterNot { it.met }.single()
            assertEquals(expected, failed.name)
            assertTrue(failed.reason.isNotBlank())
            assertTrue(verdict.summary.contains(failed.reason))
        }
    }

    @Test
    fun importedModelNeedsOnlyItsFirstLoadSpace() {
        val data = passing.copy(importedModelBytes = 3_000_000_000, phoneFreeBytes = 1_000_000_000)
        val pass = DeviceCheck.evaluate(data)
        assertTrue(pass.meetsRequirements)
        assertTrue(pass.requirements.single { it.name == "Phone storage" }.reason.contains("already imported"))
        assertFalse(DeviceCheck.evaluate(data.copy(phoneFreeBytes = 999_999_999)).meetsRequirements)
        val unknown = DeviceCheck.evaluate(data.copy(phoneFreeBytes = null)).requirements.single { it.name == "Phone storage" }
        assertFalse(unknown.met)
        assertTrue(unknown.reason.contains("could not be read"))
    }

    @Test
    fun advertisedRamCountsTowardFloorAndE4bIsAnAdvisory() {
        val pixelSized = passing.copy(totalRamBytes = 7_700_000_000, advertisedRamBytes = 8_000_000_000)
        val expected = DeviceCheck.evaluate(pixelSized)
        assertTrue(expected.meetsRequirements)
        assertEquals("Gemma 4 E4B has a 12 GB memory target.", expected.notes.single())
        val e4b = DeviceCheck.evaluate(pixelSized.copy(advertisedRamBytes = 12_000_000_000))
        assertTrue(e4b.notes.single().startsWith("Meets"))
        assertTrue(pixelSized.describeLines().any { "7.70 GB" in it && "8.00 GB advertised" in it })
    }

    @Test
    fun emulatorAndSdStatesHaveHonestLabels() {
        val emulator = passing.copy(emulator = true, abis = listOf("x86_64"))
        assertTrue(DeviceCheck.evaluate(emulator).emulator)
        assertTrue(emulator.deviceLabel.endsWith("emulator — not a phone"))
        assertFalse(passing.deviceLabel.contains("emulator"))
        assertTrue(DeviceCheck.isEmulator("ranchu", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64"))
        assertTrue(DeviceCheck.knownNoSdSlot("Google", "Pixel 9a"))
        assertTrue(DeviceCheck.knownNoSdSlot("Samsung", "SM-S926U1"))
        assertFalse(DeviceCheck.knownNoSdSlot("Samsung", "Unknown"))
        assertTrue(passing.copy(knownNoSdSlot = true).describeLines().any { "no SD slot" in it })
        assertTrue(passing.describeLines().any { "slot status unknown" in it })
        assertTrue(passing.copy(sdFreeBytes = listOf(100_000_000_000, null)).describeLines()
            .contains("Removable media 2 · unknown free"))
    }
}
