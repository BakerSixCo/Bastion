package com.bastion.terminal.map

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPackDriveTest {
    private val latitude = 43.6
    private val west = Node(10, -116.26)
    private val seam = Node(20, -116.20)
    private val east = Node(30, -116.14)

    @Test
    fun joinsExactJunctionsAndNamesIncompletePacks() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1)))
        val right = pack("East", seam.lon, east.lon, listOf(seam, east), listOf(Road(0, 0, 1)), complete = false)
        val route = drive(left, right).single()
        // The two edge IDs clash on disk. The path uses both roads without adding a connector.
        assertEquals(2, route.edgeIds.distinct().size)
        assertEquals(listOf("West", "East"), route.packsUsed)
        assertTrue(route.reducedCoverage)
        assertTrue(latitude to seam.lon in route.points)
        assertEquals(RoadGraph.meters(latitude, west.lon, latitude, east.lon), route.meters, 2.0)
    }

    @Test
    fun missingNeighbourLeavesNoPartialRouteAndCanNameItsPack() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1)))
        assertTrue(drive(left).isEmpty())
        assertEquals("Washington", CrossPackDrive.missingEndpointPack(listOf(left), 47.6588, -117.4260))
    }

    @Test
    fun proximityOrCoordinatesAloneCannotConnectBorderRoads() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1)))
        for (unrelated in listOf(seam.copy(id = 40), seam.copy(lon = seam.lon + 0.00001))) {
            val right = pack("East", seam.lon, east.lon, listOf(unrelated, east), listOf(Road(0, 0, 1)))
            assertTrue("unexpected connector at $unrelated", drive(left, right).isEmpty())
        }
    }

    @Test
    fun aPositionBlockClosesBothBorderApproaches() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(1, 0, 1)))
        val right = pack("East", seam.lon, east.lon, listOf(seam, east), listOf(Road(2, 0, 1)))
        assertTrue(drive(left, right).isNotEmpty())
        assertTrue(drive(left, right, blockedAt = listOf(latitude to seam.lon)).isEmpty())
    }

    @Test
    fun aPositionBlockClosesASideRoadWhoseEndpointsAreOutsideSnapDetail() {
        val start = west.copy(lon = -116.46)
        val left = pack("West", start.lon, seam.lon, listOf(start, seam), listOf(Road(1, 0, 1, roadClass = 6)))
        val right = pack("East", seam.lon, east.lon, listOf(seam, east), listOf(Road(2, 0, 1)))
        val packs = listOf(left, right)
        assertTrue(CrossPackDrive.routes(packs, latitude, start.lon, latitude, east.lon, count = 1).isNotEmpty())
        val block = listOf(latitude to (start.lon + seam.lon) / 2)
        assertTrue(CrossPackDrive.routes(packs, latitude, start.lon, latitude, east.lon, block, count = 1).isEmpty())
    }

    @Test
    fun duplicateGeometryCannotBypassATurnRestrictionInTheOtherPack() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1, oneway = true)))
        val right = pack(
            "East", seam.lon, east.lon, listOf(west, seam, east),
            listOf(Road(41, 0, 1, oneway = true), Road(0, 1, 2)),
            turns = listOf(TurnRule(41, seam.id, 0)),
        )
        assertTrue(drive(left, right).isEmpty())
        assertTrue(CrossPackDrive.routes(listOf(left, right), latitude, east.lon, latitude, west.lon, count = 1).isEmpty())
    }

    @Test
    fun completeJoinDoesNotClaimReducedCoverage() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1)))
        val right = pack("East", seam.lon, east.lon, listOf(seam, east), listOf(Road(0, 0, 1)))
        assertFalse(drive(left, right).single().reducedCoverage)
    }

    @Test
    fun joinsMatchingJunctionsInAdjacentCellsWithoutASharedCellKey() {
        val left = pack("West", west.lon, seam.lon, listOf(west, seam), listOf(Road(0, 0, 1)))
        val right = pack("East", seam.lon, east.lon, listOf(seam, east), listOf(Road(0, 0, 1)), cellOffsetX = 1)
        assertTrue(left.cells.intersect(right.cells).isEmpty())
        assertEquals(listOf("West", "East"), drive(left, right).single().packsUsed)
    }

    private fun drive(vararg sources: RoadPackSource, blockedAt: List<Pair<Double, Double>> = emptyList()) =
        CrossPackDrive.routes(sources.toList(), latitude, west.lon, latitude, east.lon, blockedAt, count = 1)

    private data class Node(val id: Long, val lon: Double)
    private data class Road(val id: Long, val from: Int, val to: Int, val oneway: Boolean = false, val roadClass: Int = 0)
    private data class TurnRule(val from: Long, val via: Long, val to: Long)

    private fun pack(
        name: String, westBound: Double, eastBound: Double, nodes: List<Node>, roads: List<Road>,
        complete: Boolean = true, turns: List<TurnRule> = emptyList(), cellOffsetX: Int = 0,
    ): RoadPackSource {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { d ->
            d.writeBytes("BRG4")
            d.writeInt(0)
            d.writeInt(nodes.size)
            for (node in nodes) {
                d.writeLong(node.id)
                d.writeInt((latitude * 1e6).toInt())
                d.writeInt((node.lon * 1e6).toInt())
            }
            d.writeInt(roads.size)
            for (road in roads) {
                d.writeLong(road.id)
                d.writeInt(road.from)
                d.writeInt(road.to)
                d.writeByte(road.roadClass)
                d.writeByte(if (road.oneway) 1 else 0)
                d.writeByte(60)
                d.writeByte(0)
                d.writeInt(-1)
                d.writeFloat(RoadGraph.meters(latitude, nodes[road.from].lon, latitude, nodes[road.to].lon).toFloat())
                d.writeShort(0)
            }
            d.writeInt(turns.size)
            for (rule in turns) {
                d.writeLong(rule.from)
                d.writeLong(rule.via)
                d.writeLong(rule.to)
                d.writeByte(0)
            }
            d.writeInt(0)
        }
        val grid = 9
        val key = RoadPackSource.cellKey(Geo.tileX(seam.lon, grid) + cellOffsetX, Geo.tileY(latitude, grid))
        val bytes = buffer.toByteArray()
        return RoadPackSource(name, grid, complete, westBound, latitude - 0.01, eastBound, latitude + 0.01, setOf(key)) { x, y ->
            if (RoadPackSource.cellKey(x, y) == key) bytes else null
        }
    }
}
