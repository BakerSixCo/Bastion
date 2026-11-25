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
        val flags: Int = 0,
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
    /** Turn rules by the edge they start from: (to edge, true = "only this turn"). */
    private val turns = HashMap<Long, MutableList<Pair<Long, Boolean>>>()

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
            val version = String(magic, Charsets.US_ASCII)
            require(version == "BRG1" || version == "BRG2") { "not a road file" }
            val v2 = version == "BRG2"
            val names = Array(d.readInt()) { d.readUTF() }
            val nodes = IntArray(d.readInt()) { node(d.readLong(), d.readInt(), d.readInt()) }
            repeat(d.readInt()) {
                val id = d.readLong(); val a = nodes[d.readInt()]; val b = nodes[d.readInt()]
                val cls = d.readByte().toInt(); val oneway = d.readByte().toInt() == 1; val mph = d.readByte().toInt() and 0xff
                val flags = if (v2) d.readByte().toInt() and 0xff else 0
                val ni = d.readInt(); val m = d.readFloat()
                val pts = IntArray(d.readShort().toInt() * 2) { d.readInt() }
                if (edgeSeen.add(id) && mph > 0) {
                    val e = Edge(id, a, b, cls, oneway, mph, if (ni >= 0) names[ni] else "", m, pts, flags)
                    edges += e; adj[a].add(edges.size - 1); if (b != a) adj[b].add(edges.size - 1)
                }
            }
            if (v2) repeat(d.readInt()) {
                val from = d.readLong(); val to = d.readLong(); val only = d.readByte().toInt() == 1
                val list = turns.getOrPut(from) { ArrayList() }
                if ((to to only) !in list) list += to to only
            }
        }
    }

    // ---- snapping a position onto the nearest road ----

    private class Snap(val edge: Int, val frac: Double, val dist: Double)

    /** Nearest road within [maxM] metres of a position (searches the position's cell and its neighbours). */
    private fun snap(la: Double, lo: Double, maxM: Double, skip: Set<Long> = emptySet(), ok: (Edge) -> Boolean = { true }): Snap? {
        var best: Snap? = null
        nearby(la, lo, maxM) { i, frac, dist ->
            if (edges[i].id !in skip && ok(edges[i]) && (best == null || dist < best!!.dist)) best = Snap(i, frac, dist)
        }
        return best
    }

    /** Every road passing within [radiusM] metres of a position. */
    fun roadsWithin(la: Double, lo: Double, radiusM: Double): Set<Long> {
        val out = HashSet<Long>()
        nearby(la, lo, radiusM) { i, _, _ -> out += edges[i].id }
        return out
    }

    /** Calls [hit] with (edge index, fraction along it, distance) for each road segment within [maxM] of a position. */
    private fun nearby(la: Double, lo: Double, maxM: Double, hit: (Int, Double, Double) -> Unit) {
        val cx = Geo.tileX(lo, grid); val cy = Geo.tileY(la, grid)
        for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) ensureCell(x, y)
        val kx = 111_320.0 * cos(Math.toRadians(la)); val ky = 110_574.0
        for ((i, e) in edges.withIndex()) {
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
                if (dist <= maxM) hit(i, if (total > 0) (along + t * segs[k - 1]) / total else 0.0, dist)
                along += segs[k - 1]
            }
        }
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
        blocked: Set<Long> = emptySet(), count: Int = 3, maxOffRoad: Double = 2_000.0, vehicle: Vehicle = Vehicle.CAR,
    ): List<Route> {
        val s = snap(fromLat, fromLon, maxOffRoad, blocked) { usable(it, vehicle) } ?: return emptyList()
        val t = snap(toLat, toLon, maxOffRoad, blocked) { usable(it, vehicle) } ?: return emptyList()
        val penalty = HashMap<Long, Double>()
        val out = ArrayList<Route>()
        repeat(count + 3) {
            if (out.size >= count) return out.sortedBy { it.seconds }
            val r = search(s, t, blocked, penalty, vehicle, fromLat, fromLon, toLat, toLon) ?: return out.sortedBy { it.seconds }
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

    /** What is driving: a car keeps to roads a car can use; a 4x4 also takes rough tracks. */
    enum class Vehicle(val label: String) { CAR("Car"), FOUR_BY_FOUR("4x4") }

    private fun usable(e: Edge, v: Vehicle) = when (v) {
        Vehicle.CAR -> e.flags and (FLAG_4WD or FLAG_ROUGH or FLAG_IMPASSABLE) == 0
        Vehicle.FOUR_BY_FOUR -> e.flags and FLAG_IMPASSABLE == 0
    }

    /** Seconds to drive a whole edge: the speed limit, slower on gravel and rough tracks. */
    private fun seconds(e: Edge, v: Vehicle): Double {
        var mph = e.mph
        if (e.flags and FLAG_UNPAVED != 0) mph = minOf(mph, if (v == Vehicle.CAR) 25 else 30)
        if (e.flags and FLAG_ROUGH != 0) mph = minOf(mph, 10)
        return e.meters / (mph * 0.44704)
    }

    private fun turnAllowed(fromEdge: Edge, toEdge: Edge): Boolean {
        val rules = turns[fromEdge.id] ?: return true
        if (rules.any { it.second }) return rules.any { it.second && it.first == toEdge.id }
        return rules.none { !it.second && it.first == toEdge.id }
    }

    /**
     * Edge-based A*: a state is an edge driven in one direction (ei * 2, + 1 when driven b -> a),
     * so turn rules and "no U-turn" can be checked at each junction.
     */
    private fun search(
        s: Snap, t: Snap, blocked: Set<Long>, penalty: Map<Long, Double>, v: Vehicle,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
    ): Route? {
        val se = edges[s.edge]; val te = edges[t.edge]
        if (s.edge == t.edge && (!se.oneway || t.frac >= s.frac)) {
            return build(listOf(s.edge * 2 + if (t.frac >= s.frac) 0 else 1), s, t, v, sameEdge = true)
        }
        fun cost(e: Edge) = if (e.id in blocked || !usable(e, v)) Double.POSITIVE_INFINITY
            else seconds(e, v) * (penalty[e.id] ?: 1.0) * classFactor(e.cls)
        fun arrive(st: Int): Int { val e = edges[st / 2]; return if (st % 2 == 0) e.b else e.a }
        fun h(st: Int): Double { val n = arrive(st); return meters(lat[n] / 1e6, lon[n] / 1e6, toLat, toLon) / (75 * 0.44704) }
        val g = HashMap<Int, Double>(); val via = HashMap<Int, Int>()
        val heap = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun push(st: Int, c: Double, from: Int) {
            if (c < (g[st] ?: Double.POSITIVE_INFINITY)) { g[st] = c; via[st] = from; heap += (c + h(st)) to st }
        }
        val c0 = cost(se)
        if (c0.isFinite()) {
            push(s.edge * 2, c0 * (1 - s.frac), -1)
            if (!se.oneway) push(s.edge * 2 + 1, c0 * s.frac, -1)
        }
        val ct = cost(te)
        var best = Double.POSITIVE_INFINITY; var bestState = -1
        var expanded = 0
        while (heap.isNotEmpty()) {
            val (f, st) = heap.poll()
            val gs = g[st] ?: continue
            if (f - h(st) > gs + 1e-9) continue
            if (f >= best) break
            val inEdge = edges[st / 2]; val u = arrive(st)
            // Turn onto the target road and stop at the destination.
            if (ct.isFinite() && st / 2 != t.edge && turnAllowed(inEdge, te)) {
                val extra = when {
                    u == te.a -> ct * t.frac
                    u == te.b && !te.oneway -> ct * (1 - t.frac)
                    else -> Double.POSITIVE_INFINITY
                }
                if (gs + extra < best) { best = gs + extra; bestState = st }
            }
            if (++expanded > 3_000_000) return null
            ensureAt(lat[u], lon[u])
            val list = adj[u]
            for (k in 0 until list.size) {
                val ei = list[k]; val e = edges[ei]
                if (ei == st / 2 && list.size > 1) continue          // no U-turn except at a dead end
                if (!turnAllowed(inEdge, e)) continue
                val c = cost(e); if (c.isInfinite()) continue
                if (e.a == u) push(ei * 2, gs + c, st)
                if (e.b == u && !e.oneway && e.a != e.b) push(ei * 2 + 1, gs + c, st)
            }
        }
        if (bestState < 0) return null
        val path = ArrayList<Int>()
        var st = bestState
        while (st >= 0) { path += st; st = via[st] ?: -1 }
        path.reverse()
        return build(path, s, t, v)
    }

    /** [path]: the directed edges driven, the first being the start road. */
    private fun build(path: List<Int>, s: Snap, t: Snap, v: Vehicle, sameEdge: Boolean = false): Route {
        val pts = ArrayList<Pair<Double, Double>>()
        val ids = ArrayList<Long>()
        var meters = 0.0; var secs = 0.0
        val steps = ArrayList<Step>()
        fun step(name: String, m: Double) {
            val label = name.ifBlank { "unnamed road" }
            if (steps.isNotEmpty() && steps.last().name == label) steps[steps.size - 1] = Step(label, steps.last().meters + m)
            else if (m > 0) steps += Step(label, m)
        }
        fun add(e: Edge, fromFrac: Double, toFrac: Double) {
            for (p in partial(e, fromFrac, toFrac)) if (pts.isEmpty() || pts.last() != p) pts += p
            val part = kotlin.math.abs(toFrac - fromFrac)
            meters += e.meters * part; secs += seconds(e, v) * part; ids += e.id; step(e.name, e.meters * part)
        }
        val te = edges[t.edge]
        if (sameEdge) add(edges[s.edge], s.frac, t.frac)
        else {
            path.forEachIndexed { i, st ->
                val e = edges[st / 2]; val fwd = st % 2 == 0
                val from = if (i == 0) s.frac else if (fwd) 0.0 else 1.0
                add(e, from, if (fwd) 1.0 else 0.0)
            }
            val last = edges[path.last() / 2]; val u = if (path.last() % 2 == 0) last.b else last.a
            add(te, if (u == te.a) 0.0 else 1.0, t.frac)
        }
        return Route(pts, meters, secs, steps, ids.distinct(), s.dist, t.dist)
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

    /** Parking lots, drive-throughs and alleys (service) are used to reach a place, not as shortcuts. */
    private fun classFactor(cls: Int) = if (cls == SERVICE) 3.0 else 1.0

    companion object {
        const val SERVICE = 8   // index of "service" in tools/RoadGraph.java CLASSES
        const val FLAG_4WD = 1; const val FLAG_UNPAVED = 2; const val FLAG_ROUGH = 4; const val FLAG_IMPASSABLE = 8

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
