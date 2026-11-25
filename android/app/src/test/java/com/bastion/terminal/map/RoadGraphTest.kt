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
        // At the destination, on a good fix.
        assertTrue(g.update(43.6095, -116.1900, 8f).arrived)
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
        assertTrue(blockAt.id in direct.edgeIds)
        val detour = g.routes(43.600, -116.200, 43.600, -116.160, blocked = setOf(blockAt.id), count = 1).single()
        assertFalse(blockAt.id in detour.edgeIds)
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

    // ---- regression tests for the 2026-11-25 review (docs/reviews/chatgpt-review-2026-11-25.md) ----

    /**
     * A synthetic road network written as BRG3 cells, the way tools/RoadGraph.java lays them out:
     * every edge in the cells of both its ends, every turn rule in its junction's cell.
     */
    private class Net(val grid: Int) {
        class E(val id: Long, val a: Long, val b: Long, val name: String, val mph: Int, val cls: Int, val oneway: Boolean, val meters: Float?)
        val nodes = LinkedHashMap<Long, Pair<Double, Double>>()
        val edges = ArrayList<E>()
        val rules = ArrayList<LongArray>()   // from edge, via node, to edge, only
        fun node(id: Long, la: Double, lo: Double) { nodes[id] = la to lo }
        fun edge(id: Long, a: Long, b: Long, name: String, mph: Int = 30, cls: Int = 6, oneway: Boolean = false, meters: Float? = null) {
            edges += E(id, a, b, name, mph, cls, oneway, meters)
        }
        fun rule(from: Long, via: Long, to: Long, only: Boolean) { rules += longArrayOf(from, via, to, if (only) 1 else 0) }
        fun cellOf(n: Long) = nodes[n]!!.let { (la, lo) -> Geo.tileX(lo, grid) to Geo.tileY(la, grid) }
        fun cells(): Map<Pair<Int, Int>, ByteArray> {
            val keys = (edges.flatMap { listOf(cellOf(it.a), cellOf(it.b)) } + rules.map { cellOf(it[1]) }).toSet()
            return keys.associateWith { key ->
                val es = edges.filter { cellOf(it.a) == key || cellOf(it.b) == key }
                val names = es.map { it.name }.filter { it.isNotEmpty() }.distinct()
                val ns = es.flatMap { listOf(it.a, it.b) }.distinct()
                val bo = ByteArrayOutputStream(); val o = DataOutputStream(bo)
                o.writeBytes("BRG3")
                o.writeInt(names.size); names.forEach { o.writeUTF(it) }
                o.writeInt(ns.size)
                ns.forEach { n -> val (la, lo) = nodes[n]!!; o.writeLong(n); o.writeInt(Math.round(la * 1e6).toInt()); o.writeInt(Math.round(lo * 1e6).toInt()) }
                o.writeInt(es.size)
                es.forEach { e ->
                    val (la1, lo1) = nodes[e.a]!!; val (la2, lo2) = nodes[e.b]!!
                    o.writeLong(e.id); o.writeInt(ns.indexOf(e.a)); o.writeInt(ns.indexOf(e.b))
                    o.writeByte(e.cls); o.writeByte(if (e.oneway) 1 else 0); o.writeByte(e.mph); o.writeByte(0)
                    o.writeInt(names.indexOf(e.name)); o.writeFloat(e.meters ?: RoadGraph.meters(la1, lo1, la2, lo2).toFloat()); o.writeShort(0)
                }
                val rs = rules.filter { cellOf(it[1]) == key }
                o.writeInt(rs.size)
                rs.forEach { r -> o.writeLong(r[0]); o.writeLong(r[1]); o.writeLong(r[2]); o.writeByte(r[3].toInt()) }
                o.flush(); bo.toByteArray()
            }
        }
        fun graph(): RoadGraph { val c = cells(); return RoadGraph(grid) { x, y -> c[x to y] } }
    }

    /** Built by tools/RoadGraph.java from rules.osm.xml: only_straight_on at the east end of Mid St, and a motorcar=no road. */
    private fun rulesGraph(): RoadGraph {
        val bytes = javaClass.getResourceAsStream("/roadgraph/rules-90-186.bin")!!.readBytes()
        return RoadGraph(grid) { x, y -> if (x == 90 && y == 186) bytes else null }
    }

    /** Task 1, finding 1: an "only" rule at one end of a two-way road must not restrict its other end. */
    @Test fun onlyRuleAppliesOnlyAtItsJunction() {
        val g = rulesGraph()
        // West along East St and Mid St, then right onto North A at Mid St's west end: legal.
        val r = g.routes(43.6000, -116.1800, 43.6100, -116.2000, count = 1).single()
        assertEquals(listOf("East St", "Mid St", "North A"), r.steps.map { it.name })
        // At Mid St's east end the rule still holds: from Mid St only straight on to East St, so reaching
        // North B means turning round at East St's dead end and coming back.
        val back = g.routes(43.6100, -116.2000, 43.6100, -116.1900, count = 1).single()
        assertEquals(listOf("North A", "Mid St", "East St", "North B"), back.steps.map { it.name })
        assertTrue(back.maneuvers.any { it.turn == RoadGraph.Turn.U_TURN })
    }

    /** Task 1, finding 9: access=yes with motorcar=no is closed to cars (the most specific tag wins). */
    @Test fun motorcarNoKeepsCarsOff() {
        assertEquals(null, rulesGraph().roadNear(43.6100, -116.1950))   // Private Rd is not in the network
    }

    /** Files from the BRG2 builder (turn rules without their junction) are still read and obeyed. */
    @Test fun brg2TurnRulesStillWork() {
        val bytes = javaClass.getResourceAsStream("/roadgraph/turns-brg2-90-186.bin")!!.readBytes()
        val g = RoadGraph(grid) { x, y -> if (x == 90 && y == 186) bytes else null }
        val r = g.routes(43.6000, -116.2000, 43.6095, -116.1900, count = 1).single()
        assertEquals(listOf("Main St", "Loop Rd", "North Rd"), r.steps.map { it.name })
    }

    /**
     * Main St passes through the no-left-turn junction, so it has an edge on each side. The builder must
     * pick the side that makes the turn a left: from the east the same move is a legal right turn.
     */
    @Test fun ruleOnAWayThroughTheJunctionPicksTheRightSide() {
        val r = turnsGraph().routes(43.6000, -116.1810, 43.6095, -116.1900, count = 1).single()
        assertEquals(listOf("Main St", "North Rd"), r.steps.map { it.name })
    }

    /** Task 1, finding 3: a forbidden turn onto the target road is caught even when its junction's cell was not read yet. */
    @Test fun coldAndWarmGraphsGiveTheSameRoute() {
        val net = Net(16)   // small cells (~450 m) so the junction is outside both snap windows
        net.node(1, 43.6000, -116.2300); net.node(2, 43.6000, -116.2000); net.node(3, 43.6000, -116.1700)
        net.node(4, 43.6100, -116.2300); net.node(5, 43.6100, -116.1700)
        net.edge(10, 1, 2, "West Rd"); net.edge(11, 2, 3, "Target Rd")
        net.edge(12, 1, 4, "Up Rd"); net.edge(13, 4, 5, "Top Rd"); net.edge(14, 5, 3, "Down Rd")
        net.rule(10, 2, 11, only = false)   // no straight on from West Rd onto Target Rd
        val cold = net.graph().routes(43.6000, -116.2290, 43.6000, -116.1720, count = 1).single()
        val warm = net.graph().also { it.roadNear(43.6000, -116.2000) }   // reads the junction's cell first
            .routes(43.6000, -116.2290, 43.6000, -116.1720, count = 1).single()
        assertEquals(warm.edgeIds, cold.edgeIds)
        assertEquals(listOf("West Rd", "Up Rd", "Top Rd", "Down Rd", "Target Rd"), cold.steps.map { it.name })
    }

    /**
     * Task 1, finding 4: the A* bound is measured to the snapped point on the road. Measured to an
     * off-road destination 1 km away it overestimated, and the search stopped on the slower approach.
     */
    @Test fun heuristicNeverSkipsTheFasterApproach() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2000); net.node(2, 43.6000, -116.1950)          // the target road
        net.node(3, 43.6000, -116.2100); net.node(4, 43.6000, -116.1850)          // west and east approaches
        net.node(5, 43.5900, -116.1975); net.node(6, 43.5850, -116.1975)          // junction south, start road
        net.edge(10, 1, 2, "T Rd")
        net.edge(11, 3, 1, "West Ln", meters = 905f); net.edge(12, 2, 4, "East Ln")
        net.edge(13, 5, 3, "SW Rd"); net.edge(14, 5, 4, "SE Rd"); net.edge(15, 6, 5, "Start Rd")
        val r = net.graph().routes(43.5850, -116.1975, 43.6090, -116.1995, count = 1).single()
        assertEquals(listOf("Start Rd", "SW Rd", "West Ln", "T Rd"), r.steps.map { it.name })
    }

    /** Task 1, finding 5: a U-turn is allowed where the only other road is one-way in. */
    @Test fun deadEndUTurnWhenTheOtherRoadIsOneWayIn() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2100); net.node(2, 43.6000, -116.2000); net.node(3, 43.6000, -116.1900)
        net.node(4, 43.6100, -116.1900); net.node(5, 43.6100, -116.2000)
        net.edge(10, 1, 2, "Entry Rd", oneway = true)
        net.edge(11, 2, 3, "Dead End Rd")
        net.edge(12, 4, 3, "Oneway In", oneway = true)
        net.edge(13, 2, 5, "Side Rd")
        net.rule(10, 2, 13, only = false)   // no left from Entry Rd onto Side Rd
        val r = net.graph().routes(43.6000, -116.2090, 43.6090, -116.2000, count = 1).single()
        assertEquals(listOf("Entry Rd", "Dead End Rd", "Side Rd"), r.steps.map { it.name })
        // The turn-round is an instruction, not just a line doubling back.
        assertTrue(r.maneuvers.any { it.turn == RoadGraph.Turn.U_TURN })
    }

    /** Task 1, finding 8: the stored block point is on the road, so the 50 m block always closes it. */
    @Test fun blockPointIsOnTheRoad() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2000); net.node(2, 43.6000, -116.1900)
        net.edge(10, 1, 2, "Lone Rd")
        val g = net.graph()
        val pressLat = 43.6000 + 80 / 110_574.0   // 80 m north of the road
        val hit = g.roadNear(pressLat, -116.1950)!!
        assertEquals("Lone Rd", hit.name)
        assertFalse(hit.id in g.roadsWithin(pressLat, -116.1950, 50.0))   // the press itself would miss it
        assertTrue(hit.id in g.roadsWithin(hit.lat, hit.lon, 50.0))
        assertEquals(43.6000, hit.lat, 1e-5)
    }

    /** Task 1, finding 13: alternatives are compared with the fastest route shown, not the first one found. */
    @Test fun alternativesAreNotMuchSlowerThanTheFastestShown() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2100); net.node(2, 43.6000, -116.2000); net.node(3, 43.6000, -116.1900); net.node(4, 43.6000, -116.1800)
        net.edge(10, 1, 2, "Start Rd"); net.edge(11, 3, 4, "End Rd")
        net.edge(12, 2, 3, "Plain Rd", meters = 8047f)                   // 10 min
        net.edge(13, 2, 3, "Service Ln", cls = RoadGraph.SERVICE, meters = 4828f)   // 6 min, but weighted as 18
        net.edge(14, 2, 3, "Long Rd", meters = 11265f)                   // 14 min
        val rs = net.graph().routes(43.6000, -116.2090, 43.6000, -116.1810, count = 3)
        assertEquals("Service Ln", rs.first().steps[1].name)
        rs.forEach { assertTrue("${it.seconds} vs ${rs[0].seconds}", it.seconds <= rs[0].seconds * 1.6 + 1e-6) }
    }

    /** Task 1, finding 7: the same route is not offered twice (a zero-length road made the overlap check pass). */
    @Test fun identicalRoutesAreNotRepeated() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2000); net.node(2, 43.6000, -116.1900)
        net.edge(10, 1, 2, "Zero Rd", meters = 0f)
        assertEquals(1, net.graph().routes(43.6000, -116.1980, 43.6000, -116.1920, count = 3).size)
    }

    /** Task 2, finding 1: arrival needs a good fix close to the end itself, not just a projection past it. */
    @Test fun arrivalNeedsADependableFixAtTheEnd() {
        val r = turnsGraph().routes(43.6000, -116.2000, 43.6095, -116.1900, count = 1).single()
        assertTrue(Guidance(r).update(43.6095, -116.1900, 8f).arrived)
        assertFalse(Guidance(r).update(43.6095, -116.1900).arrived)          // accuracy unknown
        assertFalse(Guidance(r).update(43.6095, -116.1900, 80f).arrived)     // poor fix
        // 33 m short and 32 m to the side: projects onto the very end of the line, but is 46 m from it.
        val s = Guidance(r).update(43.6092, -116.1896, 8f)
        assertTrue(s.remaining < 1.0)
        assertFalse(s.arrived)
    }

    /** Task 2, finding 10: no turn announcements while more than 60 m off the route. */
    @Test fun noAnnouncementsWhileOffRoute() {
        val r = turnsGraph().routes(43.6000, -116.2000, 43.6095, -116.1900, count = 1).single()
        val g = Guidance(r)
        g.update(43.6000, -116.1950, 8f)
        val off = g.update(43.6018, -116.18371, 8f)   // 200 m north of Main St, 300 m before the turn
        assertTrue(off.offRoute > 150)
        assertEquals(null, g.announcement(off, 30.0))
        // Back on the route, the same instruction is spoken.
        assertTrue(g.announcement(g.update(43.6000, -116.18371, 8f), 30.0)!!.endsWith("turn left onto Loop Rd."))
    }

    /** Task 2, finding 5: time left is the planned time of what is ahead, not a share by distance. */
    @Test fun remainingTimeIsThePlannedTimeAhead() {
        val net = Net(9)
        net.node(1, 43.6000, -116.2000); net.node(2, 43.6000, -116.1900); net.node(3, 43.6000, -116.1800)
        net.edge(10, 1, 2, "Fast Rd", mph = 60); net.edge(11, 2, 3, "Slow Rd", mph = 20)
        val r = net.graph().routes(43.6000, -116.2000, 43.6000, -116.1800, count = 1).single()
        val slow = 805.0 / (20 * 0.44704)
        assertEquals(805.0 / (60 * 0.44704) + slow, r.seconds, 2.0)
        assertEquals(slow, Guidance(r).update(43.6000, -116.1900, 8f).remainingSeconds, 2.0)
    }

    /** Task 2, finding 9: a fix older than 30 s (or of unknown age) does not start, steer or end navigation. */
    @Test fun oldFixesAreNotUsedToNavigate() {
        val now = 1_000_000L
        assertTrue(Guidance.fresh(now - 5_000, now))
        assertFalse(Guidance.fresh(now - 31_000, now))
        assertFalse(Guidance.fresh(null, now))
    }
}
