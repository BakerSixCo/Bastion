package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SunTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test
    fun midsummerNoon_isHighAndDueSouth() {
        // Redfish Lake, Idaho (44.14 N, 114.92 W). Solar noon on 21 June is about 19:42 UTC;
        // the sun then stands 90 - 44.14 + 23.44 = 69.3 degrees up, due south.
        val p = Sun.position(utc(2026, 6, 21, 19, 42), 44.1447, -114.917)
        assertEquals(69.3, p.elevation, 0.4)
        assertEquals(180.0, p.azimuth, 3.0)
    }

    @Test
    fun midwinterNoon_isLow() {
        // 21 December: 90 - 44.14 - 23.44 = 22.4 degrees at solar noon (about 19:38 UTC).
        val p = Sun.position(utc(2026, 12, 21, 19, 38), 44.1447, -114.917)
        assertEquals(22.4, p.elevation, 0.4)
        assertEquals(180.0, p.azimuth, 3.0)
    }

    @Test
    fun morningIsEast_eveningIsWest_nightIsDown() {
        val morning = Sun.position(utc(2026, 6, 21, 14, 0), 44.1447, -114.917)   // 8 am local
        val evening = Sun.position(utc(2026, 6, 22, 2, 0), 44.1447, -114.917)    // 8 pm local
        val night = Sun.position(utc(2026, 6, 21, 8, 0), 44.1447, -114.917)      // 2 am local
        assertTrue(morning.azimuth in 60.0..110.0 && morning.elevation > 10)
        assertTrue(evening.azimuth in 270.0..310.0 && evening.elevation > 0)
        assertTrue(night.elevation < 0)
    }

    @Test
    fun shade_fallsBehindARidgeAndOnSlopesFacingAway() {
        // A north-south ridge 300 m high in the middle of flat ground, cells about 27 m (zoom 12 at 44 N).
        val w = 60; val h = 20
        val el = FloatArray(w * h) { i -> val x = i % w; if (x in 28..31) 300f else 0f }
        val dem = Dem(12, Geo.tileX(-114.9, 12) * Geo.TILE, Geo.tileY(44.1, 12) * Geo.TILE, w, h, el)
        // Sun low in the east (azimuth 90, 10 degrees up): the ground west of the ridge is in its shadow,
        // the ground east of it is lit, and far enough west the shadow has ended (300 m / tan 10 = 1.7 km).
        assertTrue(dem.shaded(20, 10, 90.0, 10.0))
        assertFalse(dem.shaded(45, 10, 90.0, 10.0))
        assertFalse(dem.shaded(2, 10, 90.0, 60.0))
        // With the sun down everything is in shade.
        assertTrue(dem.shaded(45, 10, 90.0, -2.0))
    }
}
