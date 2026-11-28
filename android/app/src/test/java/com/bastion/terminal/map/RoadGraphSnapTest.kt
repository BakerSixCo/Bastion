package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import kotlin.math.cos
import kotlin.math.sqrt

class RoadGraphSnapTest {
    private val grid = 9

    @Test
    fun indexedSnapsMatchFullScanOnBuildersFixture() {
        val bytes = javaClass.getResourceAsStream("/roadgraph/90-186.bin")!!.use { it.readBytes() }
        val edges = fixtureEdges(bytes)
        val graph = RoadGraph(grid) { x, y -> bytes.takeIf { x == 90 && y == 186 } }
        // Keep every edge of the small fixture loaded while varying the query and radius.
        graph.roadsWithin(43.605, -116.19, 2_000.0)
        assertEquals(edges.size, graph.loadedEdges)
        for (row in 0..8) for (col in 0..10) {
            val lat = 43.596 + 0.0023 * row
            val lon = -116.203 + 0.0026 * col
            for (radius in doubleArrayOf(0.0, 45.0, 150.0, 2_000.0)) {
                assertLegacyHits(edges, graph, lat, lon, radius)
            }
        }
    }

    @Test
    fun longCurvedWarmEdgeAndNeighborEdgeKeepFullScanTieOrder() {
        val warm = Road(90, "Warm curved road", intArrayOf(
            20_000, -1_500_000,
            100_000, 500_000,
            20_000, 1_396_250,
            20_000, 1_406_250,
            20_000, 1_416_250,
            90_000, 1_450_000,
            20_000, -1_490_000,
        ))
        val local = Road(10, "From adjacent tile", intArrayOf(
            20_000, 1_396_250, 20_000, 1_406_250, 20_000, 1_416_250,
        ))
        val targetLon = 1.40625
        val warmX = Geo.tileX(-1.5, grid)
        val targetX = Geo.tileX(targetLon, grid)
        val y = Geo.tileY(0.02, grid)
        assertTrue(targetX - warmX > 2)
        val cells = mapOf((warmX to y) to fixtureCell(warm), (targetX - 1 to y) to fixtureCell(local))
        val graph = RoadGraph(grid) { cx, cy -> cells[cx to cy] }
        assertEquals(warm.id, graph.roadNear(0.02, -1.5)!!.id)
        assertEquals(1, graph.loadedEdges)

        val both = listOf(warm, local)
        assertLegacyHits(both, graph, 0.02, targetLon, 0.0)
        assertEquals(2, graph.loadedEdges)
        assertEquals(warm.id, graph.roadNear(0.02, targetLon, 0.0)!!.id)
        assertEquals(setOf(warm.id, local.id), graph.roadsWithin(0.02, targetLon, 0.0))
        for (delta in doubleArrayOf(-0.004, 0.0, 0.004)) {
            assertLegacyHits(both, graph, 0.021, targetLon + delta, 200.0)
        }
    }

    @Test
    fun lookupReturnsOnlyLocalCandidatesAndDeduplicatesSharedCells() {
        val index = RoadSnapIndex()
        index.add(0, -100, -100, 100, 100, intArrayOf())
        for (edge in 1..300) {
            val pos = 300_000 + edge * 61_000
            index.add(edge, pos, pos, pos + 100, pos + 100, intArrayOf())
        }
        assertEquals(listOf(0), index.nearby(0.0, 0.0, 2_000.0, 111_320.0, 110_574.0).toList())
    }

    private data class Road(val id: Long, val name: String, val line: IntArray)
    private data class Projection(val edge: Road, val fraction: Double, val distance: Double)

    private fun assertLegacyHits(edges: List<Road>, graph: RoadGraph, lat: Double, lon: Double, radius: Double) {
        val oracle = legacyScan(edges, lat, lon, radius)
        val nearest = oracle.minByOrNull { it.distance }?.let {
            val (la, lo) = legacyPointOn(it.edge.line, it.fraction)
            RoadGraph.RoadHit(it.edge.id, it.edge.name, la, lo, it.distance)
        }
        val query = "lat=$lat lon=$lon radius=$radius"
        assertEquals(query, nearest, graph.roadNear(lat, lon, radius))
        assertEquals(query, oracle.map { it.edge.id }.toSet(), graph.roadsWithin(lat, lon, radius))
    }

    /** The original unfiltered projection loop, run in fixture publication order. */
    private fun legacyScan(edges: List<Road>, la: Double, lo: Double, radius: Double): List<Projection> = buildList {
        val kx = 111_320.0 * cos(Math.toRadians(la))
        val ky = 110_574.0
        for (edge in edges) {
            val line = edge.line
            val lengths = (1 until line.size / 2).map { k ->
                val dx = (line[2 * k + 1] - line[2 * k - 1]) / 1e6 * kx
                val dy = (line[2 * k] - line[2 * k - 2]) / 1e6 * ky
                sqrt(dx * dx + dy * dy)
            }
            val total = lengths.sum()
            var along = 0.0
            for (k in 1 until line.size / 2) {
                val ax = (line[2 * k - 1] / 1e6 - lo) * kx
                val ay = (line[2 * k - 2] / 1e6 - la) * ky
                val vx = (line[2 * k + 1] / 1e6 - lo) * kx - ax
                val vy = (line[2 * k] / 1e6 - la) * ky - ay
                val len2 = vx * vx + vy * vy
                val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * vx
                val py = ay + t * vy
                val dist = sqrt(px * px + py * py)
                if (dist <= radius) {
                    add(Projection(edge, if (total > 0) (along + t * lengths[k - 1]) / total else 0.0, dist))
                }
                along += lengths[k - 1]
            }
        }
    }

    /** Original projection fraction interpreted against geodesic segment lengths for the reported point. */
    private fun legacyPointOn(line: IntArray, fraction: Double): Pair<Double, Double> {
        val n = line.size / 2
        val cum = DoubleArray(n)
        for (k in 1 until n) {
            cum[k] = cum[k - 1] + RoadGraph.meters(line[2 * k - 2] / 1e6, line[2 * k - 1] / 1e6,
                line[2 * k] / 1e6, line[2 * k + 1] / 1e6)
        }
        val meters = fraction * (cum.last().takeIf { it > 0.0 } ?: 1.0)
        var k = 1
        while (k < n - 1 && cum[k] < meters) k++
        val length = (cum[k] - cum[k - 1]).takeIf { it > 0.0 } ?: 1.0
        val u = ((meters - cum[k - 1]) / length).coerceIn(0.0, 1.0)
        return (line[2 * k - 2] + (line[2 * k] - line[2 * k - 2]) * u) / 1e6 to
            (line[2 * k - 1] + (line[2 * k + 1] - line[2 * k - 1]) * u) / 1e6
    }

    private fun fixtureEdges(bytes: ByteArray): List<Road> = DataInputStream(bytes.inputStream()).use { input ->
        val format = ByteArray(4)
        input.readFully(format)
        val hasFlags = !format.contentEquals("BRG1".toByteArray())
        val names = Array(input.readInt()) { input.readUTF() }
        val nodes = Array(input.readInt()) {
            input.readLong()
            intArrayOf(input.readInt(), input.readInt())
        }
        List(input.readInt()) {
            val id = input.readLong()
            val a = input.readInt()
            val b = input.readInt()
            input.readByte()
            input.readByte()
            assertTrue(input.readUnsignedByte() > 0)
            if (hasFlags) input.readByte()
            val name = names.getOrNull(input.readInt()).orEmpty()
            input.readFloat()
            val shape = IntArray(2 * input.readUnsignedShort()) { input.readInt() }
            Road(id, name, nodes[a] + shape + nodes[b])
        }
    }

    private fun fixtureCell(road: Road): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("BRG1")
            out.writeInt(1)
            out.writeUTF(road.name)
            out.writeInt(2)
            out.writeLong(road.id * 2)
            out.writeInt(road.line[0])
            out.writeInt(road.line[1])
            out.writeLong(road.id * 2 + 1)
            out.writeInt(road.line[road.line.size - 2])
            out.writeInt(road.line.last())
            out.writeInt(1)
            out.writeLong(road.id)
            out.writeInt(0)
            out.writeInt(1)
            out.writeByte(3)
            out.writeByte(0)
            out.writeByte(30)
            out.writeInt(0)
            out.writeFloat(1_000f)
            out.writeShort(road.line.size / 2 - 2)
            for (i in 2 until road.line.size - 2) out.writeInt(road.line[i])
        }
        return bytes.toByteArray()
    }
}
