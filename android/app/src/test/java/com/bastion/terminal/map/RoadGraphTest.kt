package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class RoadGraphTest {
    private val grid = 9

    /** The file tools/RoadGraph.java wrote from fixture.osm.xml: checks the two sides agree on the format. */
    @Test fun readsTheBuildersFileAndKeepsOneWays() {
        val bytes = javaClass.getResourceAsStream("/roadgraph/90-186.bin")!!.readBytes()
        val g = RoadGraph(grid) { x, y -> if (x == 90 && y == 186) bytes else null }
        // West end of Main St to the north end of the one-way side street (one way, northbound).
        val r = g.routes(43.6000, -116.2000, 43.6100, -116.1900, count = 1).single()
        assertEquals(listOf("Main St", "unnamed road"), r.steps.map { it.name })
        assertEquals(805.0 + 1112.0, r.meters, 15.0)
        // Main St is 35 mph (from its maxspeed tag); the side street a typical 25 mph.
        // ... plus 10 s for the left turn from Main St onto the side street.
        assertEquals(805 / (35 * 0.44704) + 1112 / (25 * 0.44704) + 10, r.seconds, 3.0)
        // Coming back the wrong way up the one-way street is not allowed, and there is no other road.
        assertTrue(g.routes(43.6100, -116.1900, 43.6000, -116.2000).isEmpty())
    }

    /** Built by tools/RoadGraph.java from turns.osm.xml: a no-left-turn from Main St onto North Rd, and a grade-4 track. */
    private fun turnsGraph(): RoadGraph {
        val bytes = javaClass.getResourceAsStream("/roadgraph/turns-90-186.bin")!!.readBytes()
        return RoadGraph(grid) { x, y -> if (x == 90 && y == 186) bytes else null }
    }

    @Test fun noLeftTurnIsObeyed() {
        // Just south of the north end of North Rd. The direct way is Main St, then left onto North Rd: forbidden.
        val r = turnsGraph().routes(43.6000, -116.2000, 43.6095, -116.1900, count = 1).single()
        assertEquals(listOf("Main St", "Loop Rd", "North Rd"), r.steps.map { it.name })
    }

    @Test fun turnInstructionsAndGuidance() {
        val r = turnsGraph().routes(43.6000, -116.2000, 43.6095, -116.1900, count = 1).single()
        // East on Main St, left (north) onto Loop Rd, which bends west, then left (south) onto North Rd.
        assertEquals(
            listOf(RoadGraph.Turn.DEPART, RoadGraph.Turn.LEFT, RoadGraph.Turn.LEFT, RoadGraph.Turn.ARRIVE),
            r.maneuvers.map { it.turn },
        )
        assertEquals(listOf("Main St", "Loop Rd", "North Rd"), r.maneuvers.dropLast(1).map { it.onto })
        assertEquals(1610.0, r.maneuvers[1].atMeters, 15.0)

        val g = Guidance(r)
        val s0 = g.update(43.6000, -116.1950)          // a quarter of the way along Main St
        assertEquals("Turn left onto Loop Rd", Guidance.instruction(s0.next))
        assertEquals(1208.0, s0.toNext, 15.0)
        assertTrue(s0.offRoute < 5)
        // 300 m before the turn at town speed: spoken once, then not again at the same distance.
        val s1 = g.update(43.6000, -116.18371)
        val said = g.announcement(s1, 30.0)!!
        assertTrue(said, said.startsWith("In ") && said.endsWith("turn left onto Loop Rd."))
        assertEquals(null, g.announcement(s1, 30.0))
        // 200 m off to the north of Main St: off the route.
        assertTrue(g.update(43.6018, -116.1950).offRoute > 150)
        // At the destination.
        assertTrue(g.update(43.6095, -116.1900).arrived)
        // "Blocked ahead" point: 100 m further along from the start is on Main St.
        val (la, lo) = g.pointAhead(0.0, 100.0)
        assertEquals(43.6000, la, 1e-5); assertTrue(lo > -116.2000)
    }

    @Test fun carsKeepOffRoughTracksButA4x4CanUseThem() {
        // A spot on Ridge Track, 300 m from any other road.
        val car = turnsGraph().routes(43.6000, -116.2000, 43.6050, -116.2000, count = 1, maxOffRoad = 100.0)
        assertTrue(car.isEmpty())
        val truck = turnsGraph().routes(43.6000, -116.2000, 43.6050, -116.2000, count = 1, maxOffRoad = 100.0,
            vehicle = RoadGraph.Vehicle.FOUR_BY_FOUR).single()
        assertEquals("Ridge Track", truck.steps.last().name)
        // Rough track: 10 mph at most.
        assertTrue(truck.seconds >= 556 / (10 * 0.44704) - 1)
    }

    // A 5 x 5 grid of two-way 30 mph streets, 0.01° apart, in one cell; returns the cell bytes.
    private fun gridCell(oneWayRow: Int = -1): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        val la0 = 43_600_000; val lo0 = -116_200_000; val step = 10_000
        fun id(i: Int, j: Int) = (i * 5 + j).toLong() + 1
        o.writeBytes("BRG1"); o.writeInt(0)
        o.writeInt(25)
        for (i in 0 until 5) for (j in 0 until 5) { o.writeLong(id(i, j)); o.writeInt(la0 + i * step); o.writeInt(lo0 + j * step) }
        val es = ArrayList<IntArray>()
        for (i in 0 until 5) for (j in 0 until 5) {
            if (j < 4) es += intArrayOf(i * 5 + j, i * 5 + j + 1, if (i == oneWayRow) 1 else 0)
            if (i < 4) es += intArrayOf(i * 5 + j, (i + 1) * 5 + j, 0)
        }
        o.writeInt(es.size)
        for ((k, e) in es.withIndex()) {
            val a = e[0]; val b = e[1]
            val m = RoadGraph.meters((la0 + a / 5 * step) / 1e6, (lo0 + a % 5 * step) / 1e6, (la0 + b / 5 * step) / 1e6, (lo0 + b % 5 * step) / 1e6)
            o.writeLong(k.toLong() + 100); o.writeInt(a); o.writeInt(b)
            o.writeByte(6); o.writeByte(e[2]); o.writeByte(30); o.writeInt(-1); o.writeFloat(m.toFloat()); o.writeShort(0)
        }
        o.flush(); return bo.toByteArray()
    }

    private fun graph(bytes: ByteArray) = RoadGraph(grid) { x, y ->
        if (x == Geo.tileX(-116.18, grid) && y == Geo.tileY(43.62, grid)) bytes else null
    }

    @Test fun fastestRouteAcrossTheGrid() {
        val g = graph(gridCell())
        val r = g.routes(43.600, -116.200, 43.640, -116.160, count = 1).single()
        // 4 blocks north + 4 blocks east, whichever way round.
        val expected = 4 * RoadGraph.meters(43.600, -116.2, 43.610, -116.2) + 4 * RoadGraph.meters(43.62, -116.2, 43.62, -116.19)
        assertEquals(expected, r.meters, expected * 0.01)
        assertEquals(43.600, r.points.first().first, 1e-6); assertEquals(43.640, r.points.last().first, 1e-6)
    }

    @Test fun alternativesAreDifferentAndNotMuchSlower() {
        val g = graph(gridCell())
        val rs = g.routes(43.600, -116.200, 43.640, -116.160, count = 3)
        assertTrue(rs.size >= 2)
        assertNotEquals(rs[0].edgeIds, rs[1].edgeIds)
        rs.forEach { assertTrue(it.seconds <= rs[0].seconds * 1.6 + 1) }
        assertTrue(rs.zipWithNext().all { (a, b) -> a.seconds <= b.seconds })
    }

    @Test fun blockingARoadFindsADetour() {
        val g = graph(gridCell())
        // Straight east along the bottom row.
        val direct = g.routes(43.600, -116.200, 43.600, -116.160, count = 1).single()
        val blockAt = g.roadNear(43.600, -116.175)!!
        assertTrue(blockAt.first in direct.edgeIds)
        val detour = g.routes(43.600, -116.200, 43.600, -116.160, blocked = setOf(blockAt.first), count = 1).single()
        assertFalse(blockAt.first in detour.edgeIds)
        assertTrue(detour.meters > direct.meters + 1000)
    }

    /** Main road A-B, 2 km, and a service lane looping 30 m beside it through C and D (found on real Boise data). */
    private fun bypassCell(): ByteArray {
        val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
        val la = 43_600_000; val north = la + 270   // about 30 m
        val nodes = listOf(la to -116_200_000, la to -116_180_000, la to -116_190_500, north to -116_190_500, north to -116_189_500, la to -116_189_500)
        o.writeBytes("BRG1"); o.writeInt(0); o.writeInt(nodes.size)
        nodes.forEachIndexed { i, (a, b) -> o.writeLong(i + 1L); o.writeInt(a); o.writeInt(b) }
        // A(0) - E(2) - F(5) - B(1) main street; E - C(3) - D(4) - F service lane.
        val es = listOf(intArrayOf(0, 2, 6), intArrayOf(2, 5, 6), intArrayOf(5, 1, 6), intArrayOf(2, 3, 8), intArrayOf(3, 4, 8), intArrayOf(4, 5, 8))
        o.writeInt(es.size)
        es.forEachIndexed { k, e ->
            val (a1, b1) = nodes[e[0]]; val (a2, b2) = nodes[e[1]]
            o.writeLong(k + 10L); o.writeInt(e[0]); o.writeInt(e[1]); o.writeByte(e[2]); o.writeByte(0); o.writeByte(if (e[2] == 8) 10 else 30)
            o.writeInt(-1); o.writeFloat(RoadGraph.meters(a1 / 1e6, b1 / 1e6, a2 / 1e6, b2 / 1e6).toFloat()); o.writeShort(0)
        }
        o.flush(); return bo.toByteArray()
    }

    @Test fun aBlockAlsoClosesTheLaneBesideIt() {
        val g = graph(bypassCell())
        // Normally the main street, not the parking-lot lane.
        val normal = g.routes(43.600, -116.200, 43.600, -116.180, count = 1).single()
        assertTrue(normal.edgeIds.none { it in 13L..15L })
        // Blocking the main street mid-block also closes the lane 30 m away: no way through.
        val blocked = g.roadsWithin(43.600, -116.190, 50.0)
        assertTrue(11L in blocked && 14L in blocked)
        assertTrue(g.routes(43.600, -116.200, 43.600, -116.180, blocked = blocked).isEmpty())
    }

    @Test fun oneWayStreetIsOnlyDrivenOneWay() {
        // Bottom row one-way eastbound (a -> b is west -> east).
        val g = graph(gridCell(oneWayRow = 0))
        val east = g.routes(43.600, -116.200, 43.600, -116.160, count = 1).single()
        val west = g.routes(43.600, -116.160, 43.600, -116.200, count = 1).single()
        assertTrue(west.meters > east.meters + 1000)  // must go round by the next row
    }

    @Test fun tooFarFromAnyRoadGivesNothing() {
        val g = graph(gridCell())
        assertTrue(g.routes(43.700, -116.200, 43.600, -116.160, maxOffRoad = 2000.0).isEmpty())
        assertTrue(g.routes(43.600, -116.200, 43.600, -116.160).first().offRoadStart < 1.0)
    }
}
