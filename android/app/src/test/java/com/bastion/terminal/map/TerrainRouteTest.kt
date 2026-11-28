package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainRouteTest {

    /** A 200×200 block at zoom 13 near 44°N (cells ≈ 13.7 m), elevation from [f]. */
    private fun dem(f: (Int, Int) -> Float): Dem {
        val z = 13
        val x0 = Geo.tileX(-115.0, z) * Geo.TILE; val y0 = Geo.tileY(44.2, z) * Geo.TILE
        val w = 200; val h = 200
        return Dem(z, x0, y0, w, h, FloatArray(w * h) { f(it % w, it / w) })
    }

    @Test fun tileMathRoundTrips() {
        assertEquals(-115.0, Geo.lon(Geo.x(-115.0)), 1e-9)
        assertEquals(44.2, Geo.lat(Geo.y(44.2)), 1e-9)
        // Known tile: Stanley, Idaho at zoom 13.
        assertEquals(1480, Geo.tileX(-114.938, 13))
        assertEquals(2971, Geo.tileY(44.216, 13))
        assertEquals(1000f, Geo.terrarium(131, 232, 0), 0.01f)
        // About 13.7 m per pixel at 44°N, zoom 13.
        assertEquals(13.75, Geo.metersPerPixel(44.0, 13), 0.1)
    }

    @Test fun walkingIsFastestSlightlyDownhillAndSlowWhenSteep() {
        val flat = Geo.walkSpeed(0.0, 100.0)
        assertEquals(5.04 / 3.6, flat, 0.01) // Tobler: about 5 km/h on the flat
        assertTrue(Geo.walkSpeed(-5.0, 100.0) > flat)
        assertTrue(Geo.walkSpeed(40.0, 100.0) < flat / 2)
        assertTrue(Geo.walkSpeed(-40.0, 100.0) < flat / 2)
    }

    @Test fun flatGroundGivesAStraightRouteWithOneLeg() {
        val d = dem { _, _ -> 1500f }
        val r = TerrainRoute.route(d, d.lat(100.5), d.lon(20.5), d.lat(100.5), d.lon(180.5))
        assertNotNull(r); r!!
        assertEquals(1, r.legs.size)
        assertEquals(90, r.legs[0].bearing) // due east
        val expected = 160 * d.cellMeters(100)
        assertEquals(expected, r.meters, expected * 0.02)
        assertEquals(0, r.climbM)
        // Off trail on the flat: 5 km/h × 0.6.
        assertEquals(expected / (5.04 / 3.6 * TerrainRoute.OFF_TRAIL), r.seconds.toDouble(), 30.0)
        assertTrue(r.legs[0].text(1).startsWith("1. 090° E for 2."))
    }

    @Test fun routeGoesAroundAWallItCannotClimb() {
        // A 300 m high wall across the middle with a gap at the south end.
        val d = dem { x, y -> if (x in 98..102 && y < 170) 1800f else 1500f }
        val r = TerrainRoute.route(d, d.lat(50.5), d.lon(40.5), d.lat(50.5), d.lon(160.5))
        assertNotNull(r); r!!
        assertTrue("goes south to the gap", r.path.any { d.py(it.lat) > 168 })
        assertTrue("never on the wall", r.path.none { p -> d.px(p.lon).toInt() in 98..102 && d.py(p.lat) < 168 })
        assertTrue(r.legs.size >= 2)
        // Legs join up: each starts where the last ended.
        r.legs.zipWithNext { a, b -> assertEquals(a.to, b.from) }
        assertEquals(r.seconds.toDouble(), r.legs.sumOf { it.seconds }.toDouble(), 2.0)
    }

    @Test fun noRouteWhenTheDestinationIsWalledIn() {
        val d = dem { x, y -> if (abs(x - 150) == 10 && abs(y - 100) <= 10 || abs(y - 100) == 10 && abs(x - 150) <= 10) 2500f else 1500f }
        assertNull(TerrainRoute.route(d, d.lat(100.5), d.lon(20.5), d.lat(100.5), d.lon(150.5)))
    }

    @Test fun outsideTheMapIsNull() {
        val d = dem { _, _ -> 1500f }
        assertNull(TerrainRoute.route(d, 10.0, 10.0, d.lat(100.5), d.lon(150.5)))
    }

    @Test fun aTrailIsPreferredEvenWhenLonger() {
        val d = dem { _, _ -> 1500f }
        val worldPx = Geo.TILE.toDouble() * Geo.tilesAcross(d.zoom)
        fun wx(px: Double) = (d.x0 + px) / worldPx
        fun wy(py: Double) = (d.y0 + py) / worldPx
        // A dog-leg trail: east along row 60, then south along column 180 — 25% longer than the straight line.
        val trail = doubleArrayOf(wx(20.5), wy(60.5), wx(180.5), wy(60.5), wx(180.5), wy(140.5))
        val mask = TerrainRoute.trailMask(d, listOf(trail))
        assertTrue(mask[60 * d.w + 100]); assertTrue(mask[100 * d.w + 180])
        val r = TerrainRoute.route(d, d.lat(60.5), d.lon(20.5), d.lat(140.5), d.lon(180.5), mask)!!
        val direct = TerrainRoute.route(d, d.lat(60.5), d.lon(20.5), d.lat(140.5), d.lon(180.5))!!
        assertTrue("follows the trail", r.legs.all { it.onTrail })
        assertTrue("trail route is longer", r.meters > direct.meters * 1.15)
        assertTrue("but quicker", r.seconds < direct.seconds)
        assertTrue(r.legs.first().text(1).contains("on trail"))
    }

    @Test fun steepnessAndShadeComeFromTheGround() {
        // A plane rising to the east at 45°.
        val flat = dem { _, _ -> 1500f }
        val d = dem { x, _ -> 1500f + x * flat.cellMeters(100).toFloat() }
        assertEquals(45f, d.slopeDeg(100, 100), 1.5f)
        assertEquals(0f, flat.slopeDeg(100, 100), 0.01f)
        assertEquals(Dem.SLOPE_CLASSES[0].second, Dem.slopeColor(3f))
        assertTrue(Dem.slopeColor(35f) != Dem.slopeColor(12f))
        // West-facing slope (rises to the east) is lit by the north-west light more than an east-facing one.
        val east = dem { x, _ -> 1500f - x * 5f }
        assertTrue(d.hillshade(100, 100) > east.hillshade(100, 100))
        assertEquals(1500f, flat.elevation(flat.lat(50.0), flat.lon(50.0))!!, 0.01f)
        assertNull(flat.elevation(0.0, 0.0))
    }

    @Test fun timeTextReadsNaturally() {
        assertEquals("1 min", TerrainRoute.timeText(20))
        assertEquals("45 min", TerrainRoute.timeText(45 * 60))
        assertEquals("2 h 05 min", TerrainRoute.timeText(125 * 60))
    }
}
