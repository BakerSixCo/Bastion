package com.bastion.terminal.map

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Driving routes over the road network in a map pack (OpenStreetMap roads cut at junctions by
 * tools/RoadGraph.java, one binary file per grid cell). Cells are read only as the search reaches
 * them, so a state-sized network is never loaded whole.
 *
 * Every route, time and distance is computed here, never by the AI. Times assume the speed limit
 * (or a typical speed for the kind of road) with no traffic. The network does not know closures,
 * washouts, snow, gates or private land: the screen says so, and the user can block a road.
 */
class RoadGraph(private val grid: Int, private val cellBytes: (Int, Int) -> ByteArray?) {

    class Edge(
        val id: Long, val a: Int, val b: Int, val cls: Int, val oneway: Boolean, val mph: Int,
        val name: String, val meters: Float, val pts: IntArray,   // lat1e6, lon1e6 pairs between a and b
    ) {
        val seconds: Double get() = meters / (mph * 0.44704)
    }

    data class Step(val name: String, val meters: Double)

    class Route(
        val points: List<Pair<Double, Double>>,   // lat, lon from start to end
        val meters: Double,
        val seconds: Double,
        val steps: List<Step>,
        val edgeIds: List<Long>,
        val offRoadStart: Double,                  // straight-line metres from the start to the road
        val offRoadEnd: Double,
    ) {
        fun summary(): String = "${miles(meters)} · ${timeText(seconds)}"
    }

    // ---- the part of the network read so far ----
    private val nodeIndex = HashMap<Long, Int>()
    private var lat = IntArray(1024); private var lon = IntArray(1024); private var nNodes = 0
    private val edges = ArrayList<Edge>()
    private val edgeSeen = HashSet<Long>()
    private val adj = ArrayList<IntArrayList>()
    private val cells = HashSet<Long>()

    val loadedEdges: Int get() = edges.size

    private fun node(osm: Long, la: Int, lo: Int): Int = nodeIndex.getOrPut(osm) {
        if (nNodes == lat.size) { lat = lat.copyOf(nNodes * 2); lon = lon.copyOf(nNodes * 2) }
        lat[nNodes] = la; lon[nNodes] = lo; adj += IntArrayList(); nNodes++; nNodes - 1
    }

    private fun ensureCell(cx: Int, cy: Int) {
        if (!cells.add((cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL))) return
        val bytes = cellBytes(cx, cy) ?: return
        runCatching { read(bytes) }
    }

    private fun ensureAt(la: Int, lo: Int) = ensureCell(Geo.tileX(lo / 1e6, grid), Geo.tileY(la / 1e6, grid))

    private fun read(bytes: ByteArray) {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val magic = ByteArray(4); d.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "BRG1") { "not a road file" }
            val names = Array(d.readInt()) { d.readUTF() }
            val nodes = IntArray(d.readInt()) { node(d.readLong(), d.readInt(), d.readInt()) }
            repeat(d.readInt()) {
                val id = d.readLong(); val a = nodes[d.readInt()]; val b = nodes[d.readInt()]
                val cls = d.readByte().toInt(); val oneway = d.readByte().toInt() == 1; val mph = d.readByte().toInt() and 0xff
                val ni = d.readInt(); val m = d.readFloat()
                val pts = IntArray(d.readShort().toInt() * 2) { d.readInt() }
                if (edgeSeen.add(id) && mph > 0) {
                    val e = Edge(id, a, b, cls, oneway, mph, if (ni >= 0) names[ni] else "", m, pts)
                    edges += e; adj[a].add(edges.size - 1); if (b != a) adj[b].add(edges.size - 1)
                }
            }
        }
    }

    // ---- snapping a position onto the nearest road ----

    private class Snap(val edge: Int, val frac: Double, val dist: Double)

    /** Nearest road within [maxM] metres of a position (searches the position's cell and its neighbours). */
    private fun snap(la: Double, lo: Double, maxM: Double, skip: Set<Long> = emptySet()): Snap? {
        val cx = Geo.tileX(lo, grid); val cy = Geo.tileY(la, grid)
        for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) ensureCell(x, y)
        var best: Snap? = null
        val kx = 111_320.0 * cos(Math.toRadians(la)); val ky = 110_574.0
        for ((i, e) in edges.withIndex()) {
            if (e.id in skip) continue
            val line = shape(e)
            var along = 0.0; var total = 0.0
            val segs = ArrayList<Double>()
            for (k in 1 until line.size / 2) {
                val dx = (line[2 * k + 1] - line[2 * k - 1]) / 1e6 * kx; val dy = (line[2 * k] - line[2 * k - 2]) / 1e6 * ky
                val s = sqrt(dx * dx + dy * dy); segs += s; total += s
            }
            for (k in 1 until line.size / 2) {
                val ax = (line[2 * k - 1] / 1e6 - lo) * kx; val ay = (line[2 * k - 2] / 1e6 - la) * ky
                val bx = (line[2 * k + 1] / 1e6 - lo) * kx; val by = (line[2 * k] / 1e6 - la) * ky
                val vx = bx - ax; val vy = by - ay; val len2 = vx * vx + vy * vy
                val t = if (len2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
                val px = ax + t * vx; val py = ay + t * vy
                val dist = sqrt(px * px + py * py)
                if (dist <= maxM && (best == null || dist < best.dist)) {
                    best = Snap(i, if (total > 0) (along + t * segs[k - 1]) / total else 0.0, dist)
                }
                along += segs[k - 1]
            }
        }
        return best
    }

    /** a, shape points, b as lat/lon ints. */
    private fun shape(e: Edge): IntArray {
        val out = IntArray(e.pts.size + 4)
        out[0] = lat[e.a]; out[1] = lon[e.a]
        e.pts.copyInto(out, 2)
        out[out.size - 2] = lat[e.b]; out[out.size - 1] = lon[e.b]
        return out
    }

    // ---- search ----

    /**
     * Up to [count] different routes, fastest first. [blocked] roads (edge ids) are never used.
     * Returns empty when either end is more than [maxOffRoad] metres from a road in this network
     * or no connection exists.
     */
    fun routes(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        blocked: Set<Long> = emptySet(), count: Int = 3, maxOffRoad: Double = 2_000.0,
    ): List<Route> {
        val s = snap(fromLat, fromLon, maxOffRoad, blocked) ?: return emptyList()
        val t = snap(toLat, toLon, maxOffRoad, blocked) ?: return emptyList()
        val penalty = HashMap<Long, Double>()
        val out = ArrayList<Route>()
        repeat(count + 3) {
            if (out.size >= count) return out.sortedBy { it.seconds }
            val r = search(s, t, blocked, penalty, fromLat, fromLon, toLat, toLon) ?: return out.sortedBy { it.seconds }
            r.edgeIds.forEach { penalty[it] = (penalty[it] ?: 1.0) * 1.6 }
            val best = out.firstOrNull()
            val distinct = out.none { o -> shared(o, r) > 0.7 * r.meters }
            if (best == null || (distinct && r.seconds <= best.seconds * 1.6)) out += r
        }
        return out.sortedBy { it.seconds }
    }

    private fun shared(a: Route, b: Route): Double {
        val ids = a.edgeIds.toHashSet()
        return b.edgeIds.filter { it in ids }.sumOf { id -> edges.first { it.id == id }.meters.toDouble() }
    }

    /** The road nearest a position (to block it), or null if none within [maxM] metres. */
    fun roadNear(la: Double, lo: Double, maxM: Double = 150.0): Pair<Long, String>? =
        snap(la, lo, maxM)?.let { edges[it.edge].id to edges[it.edge].name }

    private fun search(
        s: Snap, t: Snap, blocked: Set<Long>, penalty: Map<Long, Double>,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): Route? {
        val se = edges[s.edge]; val te = edges[t.edge]
        // Same road, and the direction is allowed: drive straight along it.
        if (s.edge == t.edge && (!se.oneway || t.frac >= s.frac)) {
            return build(listOf(), s, t, fromLat, fromLon, toLat, toLon, sameEdge = true)
        }
        val g = HashMap<Int, Double>(); val via = HashMap<Int, Int>()   // node -> edge index used to reach it
        val heap = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        val tla = toLat; val tlo = toLon
        fun h(n: Int) = meters(lat[n] / 1e6, lon[n] / 1e6, tla, tlo) / (75 * 0.44704)
        fun cost(e: Edge) = if (e.id in blocked) Double.POSITIVE_INFINITY else e.seconds * (penalty[e.id] ?: 1.0)
        // Leave the start road toward b, and toward a unless it is one-way a->b.
        fun seed(n: Int, c: Double) { if (c < (g[n] ?: Double.POSITIVE_INFINITY)) { g[n] = c; via[n] = -1; heap += (c + h(n)) to n } }
        if (se.id !in blocked) {
            seed(se.b, cost(se) * (1 - s.frac))
            if (!se.oneway) seed(se.a, cost(se) * s.frac)
        }
        // Arrive on the target road from a (always allowed) or from b (two-way only).
        val goal = HashMap<Int, Double>()
        if (te.id !in blocked) {
            goal[te.a] = cost(te) * t.frac
            if (!te.oneway) goal[te.b] = minOf(goal[te.b] ?: Double.POSITIVE_INFINITY, cost(te) * (1 - t.frac))
        }
        var best = Double.POSITIVE_INFINITY; var bestNode = -1
        var expanded = 0
        while (heap.isNotEmpty()) {
            val (f, u) = heap.poll()
            val gu = g[u] ?: continue
            if (f - h(u) > gu + 1e-9) continue
            if (f >= best) break
            goal[u]?.let { if (gu + it < best) { best = gu + it; bestNode = u } }
            if (++expanded > 3_000_000) return null
            ensureAt(lat[u], lon[u])
            val list = adj[u]
            for (k in 0 until list.size) {
                val ei = list[k]; val e = edges[ei]
                val v = if (e.a == u) e.b else if (!e.oneway) e.a else continue
                if (e.a == u && e.b == u) continue
                val c = cost(e); if (c.isInfinite()) continue
                val ng = gu + c
                if (ng < (g[v] ?: Double.POSITIVE_INFINITY)) { g[v] = ng; via[v] = ei; heap += (ng + h(v)) to v }
            }
        }
        if (bestNode < 0) return null
        val path = ArrayList<Int>()
        var n = bestNode
        while (true) { val ei = via[n] ?: break; if (ei < 0) break; path += ei; val e = edges[ei]; n = if (e.b == n) e.a else e.b }
        path.reverse()
        return build(path, s, t, fromLat, fromLon, toLat, toLon, startNode = n, endNode = bestNode)
    }

    private fun build(
        path: List<Int>, s: Snap, t: Snap, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        sameEdge: Boolean = false, startNode: Int = -1, endNode: Int = -1,
    ): Route {
        val pts = ArrayList<Pair<Double, Double>>()
        val ids = ArrayList<Long>()
        var meters = 0.0; var seconds = 0.0
        val steps = ArrayList<Step>()
        fun step(name: String, m: Double) {
            val label = name.ifBlank { "unnamed road" }
            if (steps.isNotEmpty() && steps.last().name == label) steps[steps.size - 1] = Step(label, steps.last().meters + m)
            else if (m > 0) steps += Step(label, m)
        }
        fun add(e: Edge, fromFrac: Double, toFrac: Double) {
            val line = partial(e, fromFrac, toFrac)
            for (p in line) if (pts.isEmpty() || pts.last() != p) pts += p
            val m = e.meters * kotlin.math.abs(toFrac - fromFrac)
            meters += m; seconds += m / (e.mph * 0.44704); ids += e.id; step(e.name, m)
        }
        val se = edges[s.edge]; val te = edges[t.edge]
        if (sameEdge) add(se, s.frac, t.frac)
        else {
            add(se, s.frac, if (startNode == se.b) 1.0 else 0.0)
            var at = startNode
            for (ei in path) { val e = edges[ei]; if (e.a == at) { add(e, 0.0, 1.0); at = e.b } else { add(e, 1.0, 0.0); at = e.a } }
            add(te, if (endNode == te.a) 0.0 else 1.0, t.frac)
        }
        return Route(pts, meters, seconds, steps, ids.distinct(), s.dist, t.dist)
    }

    /** The shape of an edge between two fractions of its length (from may exceed to: reversed). */
    private fun partial(e: Edge, from: Double, to: Double): List<Pair<Double, Double>> {
        val line = shape(e)
        val n = line.size / 2
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + meters(line[2 * k - 2] / 1e6, line[2 * k - 1] / 1e6, line[2 * k] / 1e6, line[2 * k + 1] / 1e6)
        val total = cum[n - 1].takeIf { it > 0 } ?: 1.0
        fun at(f: Double): Pair<Double, Double> {
            val d = f * total
            var k = 1
            while (k < n - 1 && cum[k] < d) k++
            val seg = (cum[k] - cum[k - 1]).takeIf { it > 0 } ?: 1.0
            val u = ((d - cum[k - 1]) / seg).coerceIn(0.0, 1.0)
            return (line[2 * k - 2] + (line[2 * k] - line[2 * k - 2]) * u) / 1e6 to (line[2 * k - 1] + (line[2 * k + 1] - line[2 * k - 1]) * u) / 1e6
        }
        val lo = minOf(from, to); val hi = maxOf(from, to)
        val out = ArrayList<Pair<Double, Double>>()
        out += at(lo)
        for (k in 0 until n) { val f = cum[k] / total; if (f > lo && f < hi) out += line[2 * k] / 1e6 to line[2 * k + 1] / 1e6 }
        out += at(hi)
        if (from > to) out.reverse()
        return out
    }

    /** A growable list of ints (adjacency), lighter than List<Int>. */
    class IntArrayList {
        private var a = IntArray(4); var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    companion object {
        fun meters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
            val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
            val dp = p2 - p1; val dl = Math.toRadians(lo2 - lo1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_008.8 * asin(sqrt(h))
        }

        fun miles(m: Double): String = if (m < 1609.344 * 0.2) "${(m * 3.28084).toInt()} ft" else "%.1f mi".format(m / 1609.344)

        fun timeText(s: Double): String {
            val min = (s / 60).toInt().coerceAtLeast(1)
            return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
        }
    }
}
