package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MgrsTest {

    @Test
    fun utm_matchesPublishedValues() {
        // CN Tower, Toronto — the worked example on the Wikipedia UTM page: zone 17, 630084 mE, 4833438 mN.
        val u = Mgrs.toUtm(43.642567, -79.387139)
        assertEquals(17, u.zone)
        assertEquals(630084.0, u.easting, 2.0)
        assertEquals(4833438.0, u.northing, 2.0)
    }

    @Test
    fun utm_roundTripsToWithinAMetre() {
        for ((lat, lon) in listOf(44.1447 to -114.9170, 32.7 to -117.2, 48.9 to -122.5, 25.8 to -80.2, 61.2 to -149.9)) {
            val u = Mgrs.toUtm(lat, lon)
            val (la, lo) = Mgrs.toLatLon(u.zone, u.easting, u.northing)
            assertTrue("$lat,$lon came back as $la,$lo", Geo.meters(lat, lon, la, lo) < 1.0)
        }
    }

    @Test
    fun grid_matchesPublishedValues() {
        // The Washington Monument is the standard USNG example: 18S UJ 2348 0647. The coordinates used here are
        // rounded to four places (about 10 m), so the last figure of the 10 m grid is not asserted.
        assertTrue(Mgrs.format(38.8895, -77.0352).startsWith("18S UJ 2348 064"))
        assertEquals("18S UJ 234 064", Mgrs.format(38.8895, -77.0352, 3))
        // Idaho is in zone 11 (west of 114°W) and 12; latitude band T.
        assertTrue(Mgrs.format(44.1447, -114.9170).startsWith("11T "))
        assertTrue(Mgrs.format(43.49, -112.03).startsWith("12T "))
    }
}
