package com.bastion.terminal.map

/** ZIP entry metadata and an on-demand reader. Roads stay in the pack until requested. */
internal class RoadPackSource(
    val name: String,
    val grid: Int,
    val complete: Boolean,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val cells: Set<Long>,
    val cellBytes: (Int, Int) -> ByteArray?,
) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    companion object {
        fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }
}

/** Selects packs by cell metadata. Only RoadGraph can establish a drivable connection. */
internal object CrossPackDrive {
    fun routes(
        packs: List<RoadPackSource>,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        blockedAt: List<Pair<Double, Double>> = emptyList(),
        count: Int = 3,
        vehicle: RoadGraph.Vehicle = RoadGraph.Vehicle.CAR,
    ): List<RoadGraph.Route> {
        for (plan in plans(packs, fromLat, fromLon, toLat, toLon)) {
            val graph = RoadGraph(plan.first().grid, plan)
            val blocked = blockedAt.flatMap { (lat, lon) -> graph.roadsWithin(lat, lon, 50.0) }.toSet()
            val routes = graph.routes(fromLat, fromLon, toLat, toLon, blocked, count, vehicle = vehicle)
            if (routes.isNotEmpty()) return routes
        }
        return emptyList()
    }

    private fun plans(
        packs: List<RoadPackSource>, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): List<List<RoadPackSource>> = buildList {
        for (sameGrid in packs.groupBy { it.grid }.values) {
            val starts = sameGrid.filter { it.contains(fromLat, fromLon) }
            val ends = sameGrid.filter { it.contains(toLat, toLon) }
            if (starts.isEmpty() || ends.isEmpty()) continue
            val neighbours = sameGrid.associateWith { pack -> sameGrid.filter { it !== pack && meet(pack, it) } }
            val corridor = sameGrid.filter { crosses(it, fromLat, fromLon, toLat, toLon) }.toSet()
            val fallback = ArrayList<List<RoadPackSource>>()
            for (start in starts) {
                // A third state can lie between the ends even if two packs touch far from the trip.
                val local = paths(start, corridor, neighbours)
                if (local.keys.any { it in ends } && local.size > 1) add(local.keys.toList())
                // A detour may need a pack outside the straight-line corridor.
                val all = paths(start, sameGrid.toSet(), neighbours)
                for (end in ends) all[end]?.takeIf { it.size > 1 }?.let { add(it) }
                // Metadata paths can touch without a drivable border. Let the road search explore
                // other connected candidates if the smaller plans all fail.
                if (all.size > 1 && all.keys.any { it in ends }) fallback.add(all.keys.toList())
            }
            addAll(fallback)
        }
    }.distinctBy { it.toSet() }

    private fun meet(a: RoadPackSource, b: RoadPackSource): Boolean {
        val (fewer, more) = if (a.cells.size <= b.cells.size) a to b else b to a
        return fewer.cells.any { key ->
            val x = (key shr 32).toInt()
            val y = key.toInt()
            // A complete edge stored in a boundary cell can end in the cell next door.
            (-1..1).any { dx -> (-1..1).any { dy -> RoadPackSource.cellKey(x + dx, y + dy) in more.cells } }
        }
    }

    private fun paths(
        start: RoadPackSource,
        allowed: Set<RoadPackSource>,
        neighbours: Map<RoadPackSource, List<RoadPackSource>>,
    ): Map<RoadPackSource, List<RoadPackSource>> {
        val seen = linkedMapOf(start to listOf(start))
        val queue = ArrayDeque<RoadPackSource>()
        queue += start
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            for (next in neighbours.getValue(at)) {
                if (next !in allowed || next in seen) continue
                seen[next] = seen.getValue(at) + next
                queue += next
            }
        }
        return seen
    }

    private fun crosses(pack: RoadPackSource, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Boolean {
        var start = 0.0
        var end = 1.0
        fun clip(from: Double, to: Double, min: Double, max: Double): Boolean {
            val step = to - from
            if (step == 0.0) return from in min..max
            val a = (min - from) / step
            val b = (max - from) / step
            start = maxOf(start, minOf(a, b))
            end = minOf(end, maxOf(a, b))
            return start <= end
        }
        return clip(fromLat, toLat, pack.south, pack.north) && clip(fromLon, toLon, pack.west, pack.east)
    }

    private data class State(val code: String, val name: String, val west: Double, val south: Double, val east: Double, val north: Double)

    // Kit build footprints from tools/map-build.ps1. Shared rectangles are ambiguous.
    private val kit = listOf(
        State("id", "Idaho", -117.3, 41.9, -111.0, 49.1),
        State("mt", "Montana", -116.1, 44.3, -104.0, 49.1),
        State("nv", "Nevada", -120.1, 35.0, -114.0, 42.1),
        State("or", "Oregon", -124.6, 41.9, -116.4, 46.3),
        State("ut", "Utah", -114.1, 36.9, -109.0, 42.1),
        State("wa", "Washington", -124.8, 45.5, -116.9, 49.1),
        State("wy", "Wyoming", -111.1, 40.9, -104.0, 45.1),
    )

    fun missingEndpointPack(packs: List<RoadPackSource>, lat: Double, lon: Double): String? {
        if (packs.any { it.contains(lat, lon) }) return null
        val state = kit.singleOrNull { lat in it.south..it.north && lon in it.west..it.east } ?: return null
        return state.name.takeUnless { packs.any { it.name.equals("state-" + state.code, ignoreCase = true) } }
    }
}
