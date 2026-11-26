package com.bastion.terminal.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMeasurementsTest {
    @Test
    fun missingAssetsAreReportedWithoutInventingMeasurements() {
        val result = DeviceMeasurementResult(
            model = DeviceMeasurementResult.Model(unavailable = "no model imported"),
            zim = DeviceMeasurementResult.TimedCheck(unavailable = "no ZIM present"),
            road = DeviceMeasurementResult.TimedCheck(unavailable = "no road pack present"),
            peakRssMiB = null,
            batteryBefore = DeviceMeasurementResult.Battery(null, null),
            batteryAfter = DeviceMeasurementResult.Battery(null, null),
        )
        val lines = result.describeLines()
        assertTrue("Model · not measured: no model imported" in lines)
        assertTrue("ZIM search · not measured: no ZIM present" in lines)
        assertTrue("Driving route · not measured: no road pack present" in lines)
        val json = result.toJson()
        assertTrue(json.getJSONObject("model").isNull("loadSeconds"))
        assertTrue(json.getJSONObject("zim").isNull("elapsedMs"))
        assertTrue(json.getJSONObject("road").isNull("elapsedMs"))
        assertTrue(json.isNull("peakRssMiB"))
        assertTrue(json.getJSONObject("batteryBefore").isNull("percent"))
    }

    @Test
    fun warmModelAndSearchWithoutHitsKeepTheirActualStatuses() {
        val result = DeviceMeasurementResult(
            model = DeviceMeasurementResult.Model(
                alreadyLoaded = true, backend = "CPU", promptTokensPerSecond = 25.5, decodeTokensPerSecond = 8.0,
            ),
            zim = DeviceMeasurementResult.TimedCheck(elapsedMs = 12.5, returnedResult = false),
            road = DeviceMeasurementResult.TimedCheck(elapsedMs = 2_000.0, returnedResult = true),
            peakRssMiB = 321L,
            batteryBefore = DeviceMeasurementResult.Battery(88, 29.5),
            batteryAfter = DeviceMeasurementResult.Battery(87, 30.0),
        )
        val lines = result.describeLines()
        val json = result.toJson()
        assertTrue("Model load · not measured: model already loaded" in lines)
        assertTrue("ZIM search · 12.5 ms, no hits returned" in lines)
        assertTrue(json.getJSONObject("model").getBoolean("alreadyLoaded"))
        assertEquals(8.0, json.getJSONObject("model").getDouble("decodeTokensPerSecond"), 0.0)
        assertEquals(false, json.getJSONObject("zim").getBoolean("returnedResult"))
        assertEquals(321L, json.getLong("peakRssMiB"))
        assertEquals(30.0, json.getJSONObject("batteryAfter").getDouble("temperatureC"), 0.0)
    }
}
