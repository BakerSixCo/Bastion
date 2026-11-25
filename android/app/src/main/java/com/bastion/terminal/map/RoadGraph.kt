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

    enum class Turn { DEPART, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, ARRIVE }

    /** One instruction: at [atMeters] along the route, do [turn] onto [onto] (blank if the road has no name). */
    data class Maneuver(val atMeters: Double, val lat: Double, val lon: Double, val turn: Turn, val onto: String)

    class Route(
        val points: List<Pair<Double, Double>>,   // lat, lon from start to end
        val meters: Double,
        val seconds: Double,
        val steps: List<Step>,
        val edgeIds: List<Long>,
        val offRoadStart: Double,                  // straight-line metres from the start to the road
        val offRoadEnd: Double,
        val maneuvers: List<Maneuver> = emptyList(),
        /** Planned seconds from the start to each of [points] (same size), turn delays included; empty if unknown. */
        val pointSeconds: List<Double> = emptyList(),
    ) {
        fun summary(): String = "${miles(meters)} · ${timeText(seconds)}"
    }

    /** The road nearest a position: its id and name, the closest point on it, and how far that is. */
    data class RoadHit(val id: Long, val name: String, val lat: Double, val lon: Double, val meters: Double)

    // ---- the part of the network read so far ----
    private val nodeIndex = HashMap<Long, Int>()
    private var lat = IntArray(1024); private var lon = IntArray(1024); private var osm = LongArray(1024); private var nNodes = 0
    private val edges = ArrayList<Edge>()
    private val edgeSeen = HashSet<Long>()
    private val edgeIndex = HashMap<Long, Int>()
    private val adj = ArrayList<IntArrayList>()
    private val cells = HashSet<Long>()

    /** A turn rule from one edge: at junction [via] (OSM node id; [NO_VIA] in BRG2 files) onto edge [to]; [only] = "only this turn". */
    private data class TurnRule(val via: Long, val to: Long, val only: Boolean)
    /** Turn rules by the edge they start from. */
    private val turns = HashMap<Long, MutableList<TurnRule>>()
    /** Fastest speed limit read so far (mph), for the A* bound. */
    private var maxMph = 0

    val loadedEdges: Int get() = edges.size

    private fun node(id: Long, la: Int, lo: Int): Int = nodeIndex.getOrPut(id) {
        if (nNodes == lat.size) { lat = lat.copyOf(nNodes * 2); lon = lon.copyOf(nNodes * 2); osm = osm.copyOf(nNodes * 2) }
        lat[nNodes] = la; lon[nNodes] = lo; osm[nNodes] = id; adj += IntArrayList(); nNodes++; nNodes - 1
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
            require(version == "BRG1" || version == "BRG2" || version == "BRG3") { "not a road file" }
            val v2 = version != "BRG1"           // flags and turn rules
            val v3 = version == "BRG3"           // turn rules name their junction
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
                    edges += e; edgeIndex[id] = edges.size - 1
                    adj[a].add(edges.size - 1); if (b != a) adj[b].add(edges.size - 1)
                    if (mph > maxMph) maxMph = mph
                }
            }
            if (v2) repeat(d.readInt()) {
                val from = d.readLong(); val via = if (v3) d.readLong() else NO_VIA
                val rule = TurnRule(via, d.readLong(), d.readByte().toInt() == 1)
                val list = turns.getOrPut(from) { ArrayList() }
                if (rule !in list) list += rule
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
        for (i in 0 until count + 3) {
            if (out.size >= count) break
            val r = search(s, t, blocked, penalty, vehicle) ?: break
            r.edgeIds.forEach { penalty[it] = (penalty[it] ?: 1.0) * 1.6 }
            // The same roads again (or mostly the same) are not a different route.
            val distinct = out.none { o -> o.edgeIds == r.edgeIds || shared(o, r) > 0.7 * r.meters }
            val fastest = out.minOfOrNull { it.seconds }
            if (distinct && (fastest == null || r.seconds <= fastest * 1.6)) out += r
        }
        // The search order is by weighted cost (service roads count extra), so a faster route can come
        // later: every route shown is at most 60% slower than the fastest one shown.
        val fastest = out.minOfOrNull { it.seconds } ?: return emptyList()
        return out.filter { it.seconds <= fastest * 1.6 }.sortedBy { it.seconds }
    }

    private fun shared(a: Route, b: Route): Double {
        val ids = a.edgeIds.toHashSet()
        return b.edgeIds.filter { it in ids }.sumOf { id -> edgeIndex[id]?.let { edges[it].meters.toDouble() } ?: 0.0 }
    }

    /**
     * The road nearest a position (to block it), or null if none within [maxM] metres. The hit carries
     * the closest point on that road: store that, not the pressed spot, so a block always lands on it.
     */
    fun roadNear(la: Double, lo: Double, maxM: Double = 150.0): RoadHit? =
        snap(la, lo, maxM)?.let { s ->
            val e = edges[s.edge]; val (pla, plo) = pointOn(e, s.frac)
            RoadHit(e.id, e.name, pla, plo, s.dist)
        }

    /** The position a fraction of the way along an edge. */
    private fun pointOn(e: Edge, frac: Double): Pair<Double, Double> = partial(e, frac, frac).first()

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

    /**
     * Whether the turn from [fromEdge] onto [toEdge] at junction [u] (node index) is allowed. Only the
     * rules for that junction count: an "only" rule at one end of a two-way road does not restrict the
     * other end. The junction's cell must already be read (search does that first).
     */
    private fun turnAllowed(fromEdge: Edge, u: Int, toEdge: Edge): Boolean {
        val all = turns[fromEdge.id] ?: return true
        val rules = all.filter { appliesAt(it, fromEdge, u) }
        if (rules.isEmpty()) return true
        if (rules.any { it.only }) return rules.any { it.only && it.to == toEdge.id }
        return rules.none { !it.only && it.to == toEdge.id }
    }

    private fun appliesAt(r: TurnRule, from: Edge, u: Int): Boolean {
        if (r.via != NO_VIA) return r.via == osm[u]
        // BRG2 rules have no junction: it is the one both roads meet at. Every road ending at [u] is
        // read with [u]'s cell, so a to-edge not read yet does not meet [u].
        if (from.a != u && from.b != u) return false
        val to = edgeIndex[r.to]?.let { edges[it] } ?: return false
        return to.a == u || to.b == u
    }

    /** Compass heading (degrees) of a directed edge where it leaves its start (out) or reaches its end (in). */
    private fun heading(e: Edge, forward: Boolean, atEnd: Boolean): Double {
        val l = shape(e); val n = l.size / 2
        val (i, j) = when {
            forward && atEnd -> n - 2 to n - 1
            forward -> 0 to 1
            atEnd -> 1 to 0
            else -> n - 1 to n - 2
        }
        val la1 = l[2 * i] / 1e6; val lo1 = l[2 * i + 1] / 1e6; val la2 = l[2 * j] / 1e6; val lo2 = l[2 * j + 1] / 1e6
        val dx = (lo2 - lo1) * cos(Math.toRadians(la1)); val dy = la2 - la1
        return Math.toDegrees(kotlin.math.atan2(dx, dy))
    }

    /** Signed turn angle in degrees from one directed edge onto the next: negative = left, positive = right. */
    private fun turnAngle(inState: Int, outEdge: Int, outForward: Boolean): Double {
        val a = heading(edges[inState / 2], inState % 2 == 0, atEnd = true)
        val b = heading(edges[outEdge], outForward, atEnd = false)
        var d = b - a
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /**
     * Seconds lost turning from one directed edge onto the next: nothing going straight, a little
     * for a right turn, more for a left (crossing traffic), a lot for a sharp turn or U-turn.
     */
    private fun turnSeconds(inState: Int, outEdge: Int, outForward: Boolean): Double {
        val d = turnAngle(inState, outEdge, outForward)
        val turn = kotlin.math.abs(d)
        return when {
            turn > 160 -> 60.0
            turn > 120 -> 20.0
            turn < 30 -> 0.0
            d < 0 -> 10.0      // left
            else -> 4.0        // right
        }
    }

    /**
     * Edge-based A*: a state is an edge driven in one direction (ei * 2, + 1 when driven b -> a),
     * so turn rules and "no U-turn" can be checked at each junction.
     */
    private class Queued(val f: Double, val g: Double, val st: Int)

    private fun search(s: Snap, t: Snap, blocked: Set<Long>, penalty: Map<Long, Double>, v: Vehicle): Route? {
        val se = edges[s.edge]; val te = edges[t.edge]
        if (s.edge == t.edge && (!se.oneway || t.frac >= s.frac)) {
            return build(listOf(s.edge * 2 + if (t.frac >= s.frac) 0 else 1), s, t, v, sameEdge = true)
        }
        fun cost(e: Edge) = if (e.id in blocked || !usable(e, v)) Double.POSITIVE_INFINITY
            else seconds(e, v) * (penalty[e.id] ?: 1.0) * classFactor(e.cls)
        fun arrive(st: Int): Int { val e = edges[st / 2]; return if (st % 2 == 0) e.b else e.a }
        // A* bound: straight-line distance to where the route really ends (the snapped point on the
        // target road, not the off-road destination) at the fastest speed any road can have. 1% slack
        // covers the rounding of stored lengths, so the bound never exceeds the true remaining time.
        val (tLat, tLon) = pointOn(te, t.frac)
        fun h(st: Int): Double {
            val n = arrive(st)
            return 0.99 * meters(lat[n] / 1e6, lon[n] / 1e6, tLat, tLon) / (maxOf(MAX_MPH, maxMph) * 0.44704)
        }
        val g = HashMap<Int, Double>(); val via = HashMap<Int, Int>()
        val heap = java.util.PriorityQueue<Queued>(compareBy { it.f })
        fun push(st: Int, c: Double, from: Int) {
            if (c < (g[st] ?: Double.POSITIVE_INFINITY)) { g[st] = c; via[st] = from; heap += Queued(c + h(st), c, st) }
        }
        val c0 = cost(se)
        if (c0.isFinite()) {
            push(s.edge * 2, c0 * (1 - s.frac), -1)
            if (!se.oneway) push(s.edge * 2 + 1, c0 * s.frac, -1)
        }
        val ct = cost(te)
        var best = Double.POSITIVE_INFINITY; var bestState = -1
        var expanded = 0
        fun leaves(e: Edge, u: Int) = e.a == u || (e.b == u && !e.oneway)
        while (heap.isNotEmpty()) {
            val q = heap.poll()
            val st = q.st; val gs = q.g
            if (gs > (g[st] ?: Double.POSITIVE_INFINITY)) continue      // superseded by a cheaper way here
            if (q.f >= best) break
            if (++expanded > 3_000_000) return null
            val inEdge = edges[st / 2]; val u = arrive(st)
            // Read the junction's cell before judging any exit, the turn onto the target road included:
            // its turn rules live there, so a cold and a warm graph give the same route.
            ensureAt(lat[u], lon[u])
            // Turn onto the target road and stop at the destination.
            if (ct.isFinite() && st / 2 != t.edge && turnAllowed(inEdge, u, te)) {
                val extra = when {
                    u == te.a -> ct * t.frac + turnSeconds(st, t.edge, true)
                    u == te.b && !te.oneway -> ct * (1 - t.frac) + turnSeconds(st, t.edge, false)
                    else -> Double.POSITIVE_INFINITY
                }
                if (gs + extra < best) { best = gs + extra; bestState = st }
            }
            val list = adj[u]
            // A U-turn only where nothing else can legally be driven from this arrival: a dead end, or a
            // junction whose other roads are one-way in, blocked, unusable for this vehicle or forbidden turns.
            var otherExit = false
            for (k in 0 until list.size) {
                val ei = list[k]; if (ei == st / 2) continue
                val e = edges[ei]
                if (leaves(e, u) && cost(e).isFinite() && turnAllowed(inEdge, u, e)) { otherExit = true; break }
            }
            for (k in 0 until list.size) {
                val ei = list[k]; val e = edges[ei]
                if (ei == st / 2 && otherExit) continue
                if (!turnAllowed(inEdge, u, e)) continue
                val c = cost(e); if (c.isInfinite()) continue
                if (e.a == u) push(ei * 2, gs + c + turnSeconds(st, ei, true), st)
                if (e.b == u && !e.oneway && e.a != e.b) push(ei * 2 + 1, gs + c + turnSeconds(st, ei, false), st)
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
        val times = ArrayList<Double>()   // planned seconds from the start at each point
        fun add(e: Edge, fromFrac: Double, toFrac: Double) {
            val part = kotlin.math.abs(toFrac - fromFrac)
            val edgeSecs = seconds(e, v) * part
            // One speed per edge, so time along it is in proportion to distance along it.
            val p = partial(e, fromFrac, toFrac)
            val run = DoubleArray(p.size)
            for (k in 1 until p.size) run[k] = run[k - 1] + meters(p[k - 1].first, p[k - 1].second, p[k].first, p[k].second)
            val len = run.last()
            for ((k, q) in p.withIndex()) if (pts.isEmpty() || pts.last() != q) {
                pts += q; times += secs + if (len > 0) edgeSecs * run[k] / len else 0.0
            }
            meters += e.meters * part; secs += edgeSecs; ids += e.id; step(e.name, e.meters * part)
        }
        val te = edges[t.edge]
        if (sameEdge) add(edges[s.edge], s.frac, t.frac)
        else {
            // Time lost at junctions counts toward the arrival time, as it does in the search.
            path.forEachIndexed { i, st ->
                val e = edges[st / 2]; val fwd = st % 2 == 0
                if (i > 0) secs += turnSeconds(path[i - 1], st / 2, fwd)
                val from = if (i == 0) s.frac else if (fwd) 0.0 else 1.0
                add(e, from, if (fwd) 1.0 else 0.0)
            }
            val last = edges[path.last() / 2]; val u = if (path.last() % 2 == 0) last.b else last.a
            if (path.last() / 2 != t.edge) secs += turnSeconds(path.last(), t.edge, u == te.a)
            add(te, if (u == te.a) 0.0 else 1.0, t.frac)
        }
        return Route(pts, meters, secs, steps, ids.distinct(), s.dist, t.dist, maneuvers(path, s.frac, t, sameEdge, meters, pts), times)
    }

    /**
     * Instructions for a route: one wherever the road turns by 30 degrees or more or its name changes,
     * plus depart and arrive. Distances are measured along the drawn route.
     */
    private fun maneuvers(path: List<Int>, snapStartFrac: Double, t: Snap, sameEdge: Boolean, total: Double, pts: List<Pair<Double, Double>>): List<Maneuver> {
        val first = edges[path.first() / 2]
        val out = arrayListOf(Maneuver(0.0, pts.first().first, pts.first().second, Turn.DEPART, first.name))
        if (!sameEdge) {
            var along = 0.0
            // The start road is driven from the snap point, so its length here is the part actually driven.
            val legs = ArrayList<Pair<Int, Double>>()   // (state, metres driven on it)
            path.forEachIndexed { i, st ->
                val e = edges[st / 2]; val fwd = st % 2 == 0
                val part = if (i == 0) (if (fwd) 1 - snapStartFrac else snapStartFrac) else 1.0
                legs += st to e.meters * part
            }
            val te = edges[t.edge]
            val lastSt = path.last(); val lastE = edges[lastSt / 2]; val u = if (lastSt % 2 == 0) lastE.b else lastE.a
            val next = legs.map { it.first } + listOf(t.edge * 2 + if (u == te.a) 0 else 1)
            var name = first.name
            for (i in 0 until next.size - 1) {
                along += legs[i].second
                val inSt = next[i]; val outSt = next[i + 1]
                val oe = edges[outSt / 2]
                if (outSt / 2 == inSt / 2) {
                    // Back along the same road: the U-turn at a dead end must be said, not just drawn.
                    if (outSt != inSt) {
                        val node = if (outSt % 2 == 0) oe.a else oe.b
                        out += Maneuver(along, lat[node] / 1e6, lon[node] / 1e6, Turn.U_TURN, oe.name)
                    }
                    continue
                }
                val d = turnAngle(inSt, outSt / 2, outSt % 2 == 0)
                val ad = kotlin.math.abs(d)
                val renamed = oe.name.isNotBlank() && oe.name != name
                if (ad < 30 && !renamed) continue
                val turn = when {
                    ad > 160 -> Turn.U_TURN
                    ad < 30 -> Turn.CONTINUE
                    ad < 60 -> if (d < 0) Turn.SLIGHT_LEFT else Turn.SLIGHT_RIGHT
                    ad < 130 -> if (d < 0) Turn.LEFT else Turn.RIGHT
                    else -> if (d < 0) Turn.SHARP_LEFT else Turn.SHARP_RIGHT
                }
                val node = if (outSt % 2 == 0) oe.a else oe.b
                out += Maneuver(along, lat[node] / 1e6, lon[node] / 1e6, turn, oe.name)
                name = oe.name
            }
        }
        out += Maneuver(total, pts.last().first, pts.last().second, Turn.ARRIVE, "")
        return out
    }

    /** The shape of an edge between) two fractions of its length (from may exceed to: reversed). */
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
        /** Highest speed limit tools/RoadGraph.java writes (it ignores maxspeed above 85 mph). */
        const val MAX_MPH = 85
        /** Turn rule from a BRG2 file, which does not name its junction. */
        private const val NO_VIA = Long.MIN_VALUE
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
