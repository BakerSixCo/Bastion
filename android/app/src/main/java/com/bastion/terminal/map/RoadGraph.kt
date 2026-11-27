package com.bastion.terminal.map

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToLong
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
class RoadGraph(
    private val grid: Int,
    /** False when the pack says its road network is incomplete (some cells failed to build): routes say so. */
    private val complete: Boolean = true,
    private val sourceName: String? = null,
    private val cellBytes: (Int, Int) -> ByteArray?,
) {
    internal constructor(grid: Int, sources: List<RoadPackSource>) : this(grid, cellBytes = { _, _ -> null }) {
        cross = Cross(sources)
    }

    private data class PositionedNode(val id: Long, val lat: Int, val lon: Int)

    private class Cross(val sources: List<RoadPackSource>) {
        val movedNodes = HashMap<PositionedNode, Int>()
        val geometry = LongIntMap(1 shl 12)
        val aliases = RoadLists<Long>()
        val restrictedJunctions = LongIntMap()
        val tags = arrayListOf<List<Int>>(emptyList())
        val tagIds = HashMap<List<Int>, Int>()
        var conflict = false
    }

    private var cross: Cross? = null

    class Edge(
        val id: Long, val a: Int, val b: Int, val cls: Int, var oneway: Boolean, var mph: Int,
        val name: String, val meters: Float, val pts: IntArray,   // lat1e6, lon1e6 pairs between a and b
        var flags: Int = 0,
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
        /**
         * True when the road data behind this route is known to have gaps: the pack's road network is
         * marked incomplete, or a cell could not be read and was left out whole. Roads or turn rules may
         * then be missing, so the screen must say so.
         */
        val reducedCoverage: Boolean = false,
        /** Metres actually driven on each road of [edgeIds] (to compare routes by what they share). */
        internal val driven: Map<Long, Double> = emptyMap(),
        val packsUsed: List<String> = emptyList(),
    ) {
        fun summary(): String = "${miles(meters)} · ${timeText(seconds)}"
    }

    /** The road nearest a position: its id and name, the closest point on it, and how far that is. */
    data class RoadHit(val id: Long, val name: String, val lat: Double, val lon: Double, val meters: Double)

    // ---- the part of the network read so far ----
    // Primitive maps (Primitive.kt): boxed HashMaps cost ~4x the memory on a state-size network.
    private val nodeIndex = LongIntMap(1 shl 12)
    private var lat = IntArray(1024); private var lon = IntArray(1024); private var osm = LongArray(1024); private var nNodes = 0
    private val edges = ArrayList<Edge>()
    /** Road id -> index in [edges]; [SKIPPED] for a road read but not usable (no speed). */
    private val edgeIndex = LongIntMap(1 shl 12)
    private val adjacencyHeads = PagedInts()
    private val adjacencyTails = PagedInts()
    private val adjacencyNext = PagedInts()
    /** Cells read so far: [LEVEL_MAIN] (main roads only) or [LEVEL_FULL]. */
    private val cells = LongIntMap(256)

    /** A turn rule from one edge: at junction [via] (OSM node id; [NO_VIA] in BRG2 files) onto edge [to]; [only] = "only this turn". */
    private data class TurnRule(val via: Long, val to: Long, val only: Boolean)
    /** Turn rules by the edge they start from. */
    private val turns = RoadLists<TurnRule>()
    /**
     * Turn rules through a via WAY (BRG4): from edge, then one or more via edges, then onto a to edge.
     * Kept as a trie of edge sequences (from, via1, via2, ...); a search state carries the trie node it
     * has followed so far, so the history across the via segment is known at the last junction.
     * Node 0 is the root; as a search tag, 0 means "no rule in progress".
     */
    private class RuleNode {
        val next = HashMap<Long, Int>()
        /** Edges that may not be driven next from here (end of a "no_" rule). */
        val noTo = HashSet<Long>()
        /** If any of these meets the junction, only they may be driven next ("only_" rule in progress or ending here). */
        val onlyNext = HashSet<Long>()
    }
    private val ruleNodes = arrayListOf(RuleNode())
    private val ruleStarts = LongIntMap()

    /** Fastest speed limit read so far (mph), for the A* bound. */
    private var maxMph = 0

    /** Cells that could not be read (malformed or truncated); each was left out whole. */
    var badCells = 0; private set

    val loadedEdges: Int get() = edges.size

    private fun node(id: Long, la: Int, lo: Int): Int {
        val known = nodeIndex[id]
        if (known != LongIntMap.MISSING) {
            // A shared OSM junction must also have exactly the same stored coordinates.
            val join = cross
            if (join == null || lat[known] == la && lon[known] == lo) return known
            return join.movedNodes.getOrPut(PositionedNode(id, la, lo)) { addNode(id, la, lo) }
        }
        return addNode(id, la, lo).also { nodeIndex[id] = it }
    }

    private fun addNode(id: Long, la: Int, lo: Int): Int {
        if (nNodes == lat.size) { lat = lat.copyOf(nNodes * 2); lon = lon.copyOf(nNodes * 2); osm = osm.copyOf(nNodes * 2) }
        lat[nNodes] = la; lon[nNodes] = lo; osm[nNodes] = id
        return nNodes++
    }

    /** Index in [edges] of a road id, or null if it is not read (or not usable). */
    private fun edgeAt(id: Long): Int? = edgeIndex[id].takeIf { it >= 0 }

    private fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /**
     * Reads a cell: all its roads ([full]) or only the main ones. A cell read with main roads only is
     * read again in full when a later search needs its detail (roads already read are kept once).
     */
    private fun ensureCell(cx: Int, cy: Int, full: Boolean = true) {
        val k = cellKey(cx, cy)
        val have = cells[k]
        val want = if (full) LEVEL_FULL else LEVEL_MAIN
        if (have != LongIntMap.MISSING && have >= want) return
        cells[k] = want
        // A cell is checked whole before any of it is used: a truncated turn table must not leave its
        // roads in the network without their restrictions. A bad cell is left out and counted.
        readCells(cx, cy, mainOnly = !full, countFailures = have == LongIntMap.MISSING)
    }

    private fun ensureAt(la: Int, lo: Int, full: Boolean = true) =
        ensureCell(Geo.tileX(lo / 1e6, grid), Geo.tileY(la / 1e6, grid), full)

    /** (cell, point, radius) whose side roads within the radius of the point have been read. */
    private val detailDone = HashSet<String>()

    /**
     * Reads a cell's main roads plus every other road within [radius] metres of a point (an end of a
     * route, or a position being snapped). Side roads far from any end are never read, which is what
     * keeps a state-size network in memory.
     */
    private fun ensureDetail(cx: Int, cy: Int, la: Double, lo: Double, radius: Double) {
        val k = cellKey(cx, cy)
        if (cells[k] == LEVEL_FULL) return
        ensureCell(cx, cy, full = false)
        if (!detailDone.add("$k:${(la * 1e4).roundToLong()}:${(lo * 1e4).roundToLong()}:${radius.toLong()}")) return
        val pla = (la * 1e6).toInt(); val plo = (lo * 1e6).toInt()
        readCells(cx, cy, mainOnly = true) { nla, nlo -> near(nla, nlo, pla, plo) <= radius }
    }

    private fun readCells(
        cx: Int, cy: Int, mainOnly: Boolean, countFailures: Boolean = false,
        keepSide: ((Int, Int) -> Boolean)? = null,
    ) {
        fun read(bytes: ByteArray?, source: Int) {
            val cell = bytes?.let { runCatching { parse(it, source, mainOnly, keepSide) }.getOrNull() }
            if (cell != null) publish(cell) else if (countFailures) badCells++
        }
        val joined = cross
        if (joined == null) {
            val bytes = cellBytes(cx, cy) ?: return
            read(bytes, 0)
            return
        }
        val key = cellKey(cx, cy)
        for ((i, source) in joined.sources.withIndex()) {
            if (key !in source.cells) continue
            read(source.cellBytes(cx, cy), i + 1)
        }
    }

    /** Approximate metres between two positions in 1e-6 degrees (fine at [DETAIL_M] scale). */
    private fun near(la1: Int, lo1: Int, la2: Int, lo2: Int): Double {
        val dy = (la1 - la2) * 0.110_574; val dx = (lo1 - lo2) * 0.111_320 * cos(Math.toRadians(la1 / 1e6))
        return sqrt(dx * dx + dy * dy)
    }

    private class RawEdge(
        val id: Long, val a: Int, val b: Int, val cls: Int, val oneway: Boolean, val mph: Int, val flags: Int,
        val name: String, val meters: Float, val pts: IntArray,
    )
    private class PathRule(val seq: LongArray, val to: Long, val only: Boolean)   // seq = from edge, via edges...
    private class Cell(
        val nodeIds: LongArray, val nodeLat: IntArray, val nodeLon: IntArray,
        val edges: List<RawEdge>, val turns: List<Pair<Long, TurnRule>>, val paths: List<PathRule>,
    )

    /** Reads and checks a whole cell file; throws if any part is malformed. Changes nothing. */
    private fun parse(bytes: ByteArray, source: Int, mainOnly: Boolean, keepSide: ((Int, Int) -> Boolean)?): Cell {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            // Pack builders allocate nonnegative ordinal road IDs independently. Leave node IDs
            // global and reserve the high bits of a road ID for its pack in a joined search.
            fun roadId(): Long {
                val id = d.readLong()
                if (source == 0) return id
                require(id in 0L..LOCAL_EDGE_MASK) { "bad pack-local edge id" }
                return (source.toLong() shl LOCAL_EDGE_BITS) or id
            }
            val magic = ByteArray(4); d.readFully(magic)
            val version = String(magic, Charsets.US_ASCII)
            require(version == "BRG1" || version == "BRG2" || version == "BRG3" || version == "BRG4") { "not a road file" }
            val v2 = version != "BRG1"                         // flags and turn rules
            val v3 = version == "BRG3" || version == "BRG4"    // turn rules name their junction
            val v4 = version == "BRG4"                         // turn rules through a via way
            fun count(): Int = d.readInt().also { require(it in 0..bytes.size) { "bad count" } }
            val names = Array(count()) { d.readUTF() }
            val nn = count()
            val ids = LongArray(nn); val las = IntArray(nn); val los = IntArray(nn)
            for (i in 0 until nn) {
                ids[i] = d.readLong(); las[i] = d.readInt(); los[i] = d.readInt()
                require(las[i] in -90_000_000..90_000_000 && los[i] in -180_000_000..180_000_000) { "bad position" }
            }
            val es = ArrayList<RawEdge>()
            repeat(count()) {
                val id = roadId(); val a = d.readInt(); val b = d.readInt()
                require(a in 0 until nn && b in 0 until nn) { "bad node index" }
                val cls = d.readByte().toInt(); val oneway = d.readByte().toInt() == 1; val mph = d.readByte().toInt() and 0xff
                val flags = if (v2) d.readByte().toInt() and 0xff else 0
                val ni = d.readInt(); val m = d.readFloat()
                require(ni in -1 until names.size && m.isFinite() && m >= 0f) { "bad edge" }
                val np = d.readShort().toInt(); require(np >= 0) { "bad shape" }
                val wanted = !mainOnly || isMain(cls) ||
                    (keepSide != null && (keepSide(las[a], los[a]) || keepSide(las[b], los[b])))
                if (!wanted || edgeIndex.containsKey(id)) {
                    // Shape coordinates are stored as ints. Their byte count still has to fit,
                    // and every turn rule is still validated before the cell can publish anything.
                    val size = np * 2 * Int.SIZE_BYTES
                    require(d.skipBytes(size) == size) { "truncated shape" }
                    return@repeat
                }
                val pts = if (np == 0) EMPTY_SHAPE else IntArray(np * 2) { d.readInt() }
                es += RawEdge(id, a, b, cls, oneway, mph, flags, if (ni >= 0) names[ni] else "", m, pts)
            }
            val ts = ArrayList<Pair<Long, TurnRule>>()
            if (v2) repeat(count()) {
                val from = roadId(); val via = if (v3) d.readLong() else NO_VIA
                ts += from to TurnRule(via, roadId(), d.readByte().toInt() == 1)
            }
            val ps = ArrayList<PathRule>()
            if (v4) repeat(count()) {
                val from = roadId(); val n = d.readUnsignedByte(); require(n >= 1) { "via way rule without via" }
                val seq = LongArray(n + 1); seq[0] = from
                for (k in 1..n) seq[k] = roadId()
                ps += PathRule(seq, roadId(), d.readByte().toInt() == 1)
            }
            require(d.available() == 0) { "trailing bytes" }
            return Cell(ids, las, los, es, ts, ps)
        }
    }

    private val snapIndex = RoadSnapIndex()
    private var indexedCrossEdges = 0

    /** Adds a checked cell to the network read so far (each road once, by id). */
    private fun publish(c: Cell) {
        // Nodes only for the roads kept: a main-roads-only cell must not pay for its side streets' nodes.
        val nodes = IntArray(c.nodeIds.size) { -1 }
        fun n(i: Int): Int { if (nodes[i] < 0) nodes[i] = node(c.nodeIds[i], c.nodeLat[i], c.nodeLon[i]); return nodes[i] }
        for (r in c.edges) {
            if (edgeIndex.containsKey(r.id)) continue
            if (r.mph <= 0) { edgeIndex[r.id] = SKIPPED; continue }
            val a = n(r.a); val b = n(r.b)
            val e = Edge(r.id, a, b, r.cls, r.oneway, r.mph, r.name, r.meters, r.pts, r.flags)
            val index = edges.size
            if (cross?.let { alias(it, e, index) } == true) continue
            edges += e; edgeIndex[r.id] = index
            if (cross == null) snapIndex.add(index, lat[a], lon[a], lat[b], lon[b], e.pts)
            appendAdjacent(a, index)
            if (b != a) appendAdjacent(b, index)
            if (r.mph > maxMph) maxMph = r.mph
        }
        for ((from, rule) in c.turns) {
            val list = turns.getOrAdd(from)
            if (rule !in list) list += rule
            if (rule.via != NO_VIA) cross?.restrictedJunctions?.set(rule.via, 1)
        }
        for (p in c.paths) {
            addPathRule(p.seq, p.to, p.only)
            val junctions = cross?.restrictedJunctions ?: continue
            for (raw in p.seq) edgeAt(raw)?.let { i ->
                junctions[osm[edges[i].a]] = 1
                junctions[osm[edges[i].b]] = 1
            }
        }
    }

    private fun sourceOf(id: Long): Int = (id ushr LOCAL_EDGE_BITS).toInt()

    private inline fun forRoadIds(id: Long, action: (Long) -> Unit) {
        action(id)
        val aliases = cross?.aliases?.get(id) ?: return
        for (raw in aliases) action(raw)
    }

    /**
     * An identical polyline at the same two OSM nodes is the same road even if each pack
     * assigned a different ordinal. Keep both rule IDs but one searchable directed edge.
     */
    private fun alias(join: Cross, edge: Edge, next: Int): Boolean {
        val reverse = edge.a > edge.b
        var key = minOf(edge.a, edge.b).toLong() shl 32 or maxOf(edge.a, edge.b).toLong()
        for (i in edge.pts.indices step 2) {
            val j = if (reverse) edge.pts.size - 2 - i else i
            key = (key xor edge.pts[j].toLong()) * 1099511628211L
            key = (key xor edge.pts[j + 1].toLong()) * 1099511628211L
        }
        val index = join.geometry[key]
        if (index == LongIntMap.MISSING) {
            join.geometry[key] = next
            return false
        }
        val other = edges[index]
        val same = edge.a == other.a && edge.b == other.b
        if (!same && (edge.a != other.b || edge.b != other.a)) return false
        if (edge.pts.size != other.pts.size) return false
        for (i in edge.pts.indices step 2) {
            val j = if (same) i else edge.pts.size - 2 - i
            if (edge.pts[i] != other.pts[j] || edge.pts[i + 1] != other.pts[j + 1]) return false
        }
        forRoadIds(other.id) { if (sourceOf(it) == sourceOf(edge.id)) return false }
        join.aliases.getOrAdd(other.id).add(edge.id)
        edgeIndex[edge.id] = index
        // If snapshots disagree on direction, keep only the direction representable by
        // the existing edge. An incompatible reverse-only copy is conservatively closed.
        if (!same && edge.oneway) {
            other.flags = other.flags or FLAG_IMPASSABLE
            join.conflict = true
        } else {
            other.oneway = other.oneway || edge.oneway
        }
        other.flags = other.flags or edge.flags
        other.mph = minOf(other.mph, edge.mph)
        return true
    }

    private fun addPathRule(seq: LongArray, to: Long, only: Boolean) {
        fun newRuleNode(): Int {
            ruleNodes += RuleNode()
            return ruleNodes.lastIndex
        }
        var n = child(0, seq[0]).takeIf { it != 0 } ?: newRuleNode().also { ruleStarts[seq[0]] = it }
        for (k in 1 until seq.size) {
            // An "only" rule fixes the next edge after its from edge and each via edge.
            if (only) ruleNodes[n].onlyNext += seq[k]
            n = ruleNodes[n].next.getOrPut(seq[k], ::newRuleNode)
        }
        if (only) ruleNodes[n].onlyNext += to else ruleNodes[n].noTo += to
    }

    /** The trie node reached by driving [edge] after [node] (0 if no rule continues that way). */
    private fun child(node: Int, edge: Long): Int =
        if (node == 0) ruleStarts[edge].coerceAtLeast(0) else ruleNodes[node].next[edge] ?: 0

    /** The via-way rule progress after leaving [inEdge] (with progress [tag]) onto [out]. */
    private fun nextTag(tag: Int, inEdge: Long, out: Long): Int {
        cross?.let { return nextCrossTag(it, tag, inEdge, out) }
        if (tag != 0) child(tag, out).let { if (it != 0) return it }
        val f = child(0, inEdge)
        return if (f != 0) child(f, out) else 0
    }

    private inline fun forRuleNodes(join: Cross, inEdge: Long, tag: Int, action: (Int) -> Unit) {
        for (node in join.tags[tag]) action(node)
        forRoadIds(inEdge) { raw -> child(0, raw).let { if (it != 0) action(it) } }
    }

    private fun nextCrossTag(join: Cross, tag: Int, inEdge: Long, out: Long): Int {
        var next: MutableList<Int>? = null
        forRuleNodes(join, inEdge, tag) { node ->
            forRoadIds(out) { raw ->
                val child = child(node, raw)
                if (child != 0) {
                    val list = next ?: ArrayList<Int>(2).also { next = it }
                    if (child !in list) list.add(child)
                }
            }
        }
        val key = next ?: return 0
        key.sort()
        return join.tagIds.getOrPut(key) { join.tags.add(key); join.tags.lastIndex }
    }

    private fun touches(edgeId: Long, u: Int): Boolean = edgeAt(edgeId)?.let { edges[it].a == u || edges[it].b == u } ?: false

    /** Whether via-way rules allow leaving [inEdge] (progress [tag]) onto [out] at junction [u]. */
    private fun pathAllowed(inEdge: Long, tag: Int, u: Int, out: Long): Boolean {
        cross?.let { join ->
            forRuleNodes(join, inEdge, tag) { node ->
                val rule = ruleNodes[node]
                if (rule.noTo.any { matchesRoadAt(it, out, u) }) return false
                if (rule.onlyNext.any { touches(it, u) } && rule.onlyNext.none { sameRoad(it, out) }) return false
                // A differently split copy of the next via edge cannot carry this rule's history.
                if (rule.next.keys.any { !sameRoad(it, out) && matchesRoadAt(it, out, u) }) return false
                // Do not drop active history by switching to a pack that cannot represent a
                // restricted approach. Identical edges carry their identities in both packs.
                if (!shareSource(inEdge, out)) return false
            }
            return true
        }
        val f = child(0, inEdge)
        if (tag == 0 && f == 0) return true
        for (n in intArrayOf(tag, f)) {
            if (n == 0) continue
            val r = ruleNodes[n]
            if (out in r.noTo) return false
            if (r.onlyNext.isNotEmpty()) {
                // Only where the rule's next edge meets this junction (a from edge has two ends).
                val here = r.onlyNext.filter { touches(it, u) }
                if (here.isNotEmpty() && out !in here) return false
            }
        }
        return true
    }

    private fun shareSource(a: Long, b: Long): Boolean {
        forRoadIds(a) { left ->
            forRoadIds(b) { right -> if (sourceOf(left) == sourceOf(right)) return true }
        }
        return false
    }

    private fun sameRoad(raw: Long, canonical: Long): Boolean =
        raw == canonical || edgeAt(raw)?.let { edges[it].id == canonical } == true

    private fun matchesRoadAt(raw: Long, canonical: Long, u: Int): Boolean {
        if (sameRoad(raw, canonical)) return true
        val a = edgeAt(raw)?.let(edges::get) ?: return false
        val b = edgeAt(canonical)?.let(edges::get) ?: return false
        return arm(a, u)?.let { it == arm(b, u) } == true
    }

    /** First segment out of a junction. Shared arms with different far ends have different cuts. */
    private fun arm(e: Edge, u: Int): Long? {
        val forward = e.a == u
        if (!forward && e.b != u) return null
        if (e.pts.isEmpty()) {
            val end = if (forward) e.b else e.a
            return cellKey(lat[end], lon[end])
        }
        val i = if (forward) 0 else e.pts.size - 2
        return cellKey(e.pts[i], e.pts[i + 1])
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

    /**
     * Up to [max] nearby roads within [maxM] metres, nearest first, each at its closest point: at most
     * two pieces of one named road, so a road cut into short pieces does not crowd out a different one.
     */
    private fun candidates(la: Double, lo: Double, maxM: Double, skip: Set<Long>, max: Int, ok: (Edge) -> Boolean): List<Snap> {
        val bestPer = HashMap<Int, Snap>()
        nearby(la, lo, maxM) { i, frac, dist ->
            if (edges[i].id !in skip && ok(edges[i]) && dist < (bestPer[i]?.dist ?: Double.POSITIVE_INFINITY)) bestPer[i] = Snap(i, frac, dist)
        }
        val out = ArrayList<Snap>(); val perName = HashMap<String, Int>()
        for (s in bestPer.values.sortedBy { it.dist }) {
            val name = edges[s.edge].name
            if (name.isNotEmpty() && (perName[name] ?: 0).also { perName[name] = it + 1 } >= 2) continue
            out += s
            if (out.size == max) break
        }
        return out
    }

    /** Every road passing within [radiusM] metres of a position. */
    fun roadsWithin(la: Double, lo: Double, radiusM: Double): Set<Long> {
        val out = HashSet<Long>()
        nearby(la, lo, radiusM) { i, _, _ -> out += edges[i].id }
        return out
    }

    /** Include side roads whose geometry crosses the block even if neither endpoint is nearby. */
    internal fun roadsForBlock(la: Double, lo: Double): Set<Long> {
        val radius = 50.0
        val dy = radius / 110_574.0
        val dx = radius / (111_320.0 * cos(Math.toRadians(la))).coerceAtLeast(1.0)
        // Pack cells include each road whose geometry crosses them.
        val xs = Geo.tileX(lo - dx, grid)..Geo.tileX(lo + dx, grid)
        val ys = Geo.tileY(la + dy, grid)..Geo.tileY(la - dy, grid)
        for (x in xs) for (y in ys) ensureCell(x, y)
        return roadsWithin(la, lo, radius)
    }

    /** Calls [hit] with (edge index, fraction along it, distance) for each road segment within [maxM] of a position. */
    private fun nearby(la: Double, lo: Double, maxM: Double, hit: (Int, Double, Double) -> Unit) {
        val cx = Geo.tileX(lo, grid); val cy = Geo.tileY(la, grid)
        for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) ensureDetail(x, y, la, lo, DETAIL_M[0])
        // A joined graph is temporary. Most of the network is loaded after its endpoint snaps;
        // index those roads only if a later query needs them, in the same publication order.
        if (cross != null) while (indexedCrossEdges < edges.size) {
            val e = edges[indexedCrossEdges]
            snapIndex.add(indexedCrossEdges, lat[e.a], lon[e.a], lat[e.b], lon[e.b], e.pts)
            indexedCrossEdges++
        }
        val kx = 111_320.0 * cos(Math.toRadians(la)); val ky = 110_574.0
        for (i in snapIndex.nearby(la, lo, maxM, kx, ky)) {
            val e = edges[i]
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
        val ok = { e: Edge -> usable(e, vehicle) }
        // The nearest road at each end first: that is where the driver almost always is.
        val s1 = candidates(fromLat, fromLon, maxOffRoad, blocked, 1, ok)
        val t1 = candidates(toLat, toLon, maxOffRoad, blocked, 1, ok)
        if (s1.isEmpty() || t1.isEmpty()) return emptyList()
        val first = routesBetween(s1, t1, blocked, count, vehicle)
        if (first.isNotEmpty()) return first
        // No route from the nearest roads (an isolated frontage road, say): try the other roads within
        // reach at both ends together. The walk to each road is priced, and shown as the off-road distance.
        // Only roads not much further off than the nearest: a road 1 km away is not where the driver is.
        val sN = candidates(fromLat, fromLon, minOf(maxOffRoad, s1[0].dist + SNAP_SLACK_M), blocked, SNAP_CANDIDATES, ok)
        val tN = candidates(toLat, toLon, minOf(maxOffRoad, t1[0].dist + SNAP_SLACK_M), blocked, SNAP_CANDIDATES, ok)
        if (sN.size == 1 && tN.size == 1) return emptyList()
        return routesBetween(sN, tN, blocked, count, vehicle)
    }

    private fun routesBetween(starts: List<Snap>, targets: List<Snap>, blocked: Set<Long>, count: Int, vehicle: Vehicle): List<Route> {
        val penalty = HashMap<Long, Double>()
        val out = ArrayList<Route>()
        val states = SearchStates()
        // Main roads between the ends, side roads only near them. If that finds nothing (an end reached
        // only by a long track or side road), a wider area, then every road: heavier, but only then.
        var tier = 0
        for (i in 0 until count + 3) {
            if (out.size >= count) break
            var r = search(starts, targets, blocked, penalty, vehicle, DETAIL_M[tier], states)
            while (r == null && out.isEmpty() && tier < DETAIL_M.size - 1)
                r = search(starts, targets, blocked, penalty, vehicle, DETAIL_M[++tier], states)
            if (r == null) break
            // Start and end at the same spot on the road: there is nothing to drive and no alternative.
            if (r.meters < ZERO_M) return if (out.isEmpty()) listOf(r) else out
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

    /** Metres of road two routes both drive (a road driven only in part counts for the part driven). */
    private fun shared(a: Route, b: Route): Double {
        val ids = a.edgeIds.toHashSet()
        fun full(id: Long) = edgeAt(id)?.let { edges[it].meters.toDouble() } ?: 0.0
        return b.edgeIds.filter { it in ids }.sumOf { id -> minOf(a.driven[id] ?: full(id), b.driven[id] ?: full(id)) }
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
        cross?.let { return crossTurnAllowed(it, fromEdge, u, toEdge) }
        val all = turns[fromEdge.id] ?: return true
        var only = false
        var prohibited = false
        for (rule in all) {
            if (!appliesAt(rule, fromEdge, u)) continue
            if (rule.only) {
                if (rule.to == toEdge.id) return true
                only = true
            } else if (rule.to == toEdge.id) prohibited = true
        }
        return !only && !prohibited
    }

    private fun crossTurnAllowed(join: Cross, from: Edge, u: Int, to: Edge): Boolean {
        forRoadIds(from.id) { raw ->
            val rules = turns[raw] ?: return@forRoadIds
            var only = false
            var allowed = false
            for (rule in rules) {
                if (!appliesAt(rule, from, u)) continue
                if (rule.only) {
                    only = true
                    if (sameRoad(rule.to, to.id)) allowed = true
                } else if (matchesRoadAt(rule.to, to.id, u)) return false
            }
            if (only && !allowed) return false
        }
        // Different extracts can cut the same approach at different far junctions.
        // If an alternate cut carries a restriction, do not infer a legal turn from it.
        if (join.restrictedJunctions.containsKey(osm[u])) {
            val inbound = arm(from, u)
            forEachAdjacent(u) { index ->
                val other = edges[index]
                if (other.id == from.id || arm(other, u) != inbound) return@forEachAdjacent
                forRoadIds(other.id) { raw ->
                    if (turns[raw]?.any { appliesAt(it, other, u) } == true || child(0, raw) != 0) return false
                }
            }
        }
        return true
    }

    private fun appliesAt(r: TurnRule, from: Edge, u: Int): Boolean {
        if (r.via != NO_VIA) return r.via == osm[u]
        // BRG2 rules have no junction: it is the one both roads meet at. Every road ending at [u] is
        // read with [u]'s cell, so a to-edge not read yet does not meet [u].
        if (from.a != u && from.b != u) return false
        val to = edgeAt(r.to)?.let { edges[it] } ?: return false
        return to.a == u || to.b == u
    }

    /** Compass heading (degrees) of a directed edge where it leaves its start (out) or reaches its end (in). */
    private fun heading(e: Edge, forward: Boolean, atEnd: Boolean): Double {
        // Points of the road: a, shape points, b (read in place: this runs for every turn the search tries).
        val n = e.pts.size / 2 + 2
        fun la(i: Int) = when (i) { 0 -> lat[e.a]; n - 1 -> lat[e.b]; else -> e.pts[2 * (i - 1)] }
        fun lo(i: Int) = when (i) { 0 -> lon[e.a]; n - 1 -> lon[e.b]; else -> e.pts[2 * (i - 1) + 1] }
        val i: Int; val j: Int
        when {
            forward && atEnd -> { i = n - 2; j = n - 1 }
            forward -> { i = 0; j = 1 }
            atEnd -> { i = 1; j = 0 }
            else -> { i = n - 1; j = n - 2 }
        }
        val la1 = la(i) / 1e6; val lo1 = lo(i) / 1e6; val la2 = la(j) / 1e6; val lo2 = lo(j) / 1e6
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
     * A search state key. It packs the directed edge (low 32 bits: edge * 2, + 1 when driven
     * b -> a) with the via-way rule progress (high 32 bits, a [ruleNodes] index; 0 = none).
     */
    private fun key(st: Int, tag: Int): Long = (tag.toLong() shl 32) or st.toLong()
    private fun stOf(key: Long): Int = (key and 0xffffffffL).toInt()
    private fun tagOf(key: Long): Int = (key ushr 32).toInt()

    /**
     * Edge-based A* from any of [starts] to any of [targets] (snapped points; the walk to each road is
     * priced as [accessSeconds]). A state is an edge driven in one direction, so turn rules and "no
     * U-turn" can be checked at each junction; it also carries the progress through any via-way rule.
     * Driving straight along one road, when a start and a target are on the same road, is one priced
     * candidate among the others, so the penalties that make alternatives apply to it too.
     */
    private fun search(
        starts: List<Snap>, targets: List<Snap>, blocked: Set<Long>, penalty: Map<Long, Double>,
        v: Vehicle, detailM: Double, states: SearchStates,
    ): Route? {
        val allRoads = detailM.isInfinite()
        fun cost(e: Edge) = if (e.id in blocked || !usable(e, v)) Double.POSITIVE_INFINITY
            else seconds(e, v) * (penalty[e.id] ?: 1.0) * classFactor(e.cls)
        fun arrive(st: Int): Int { val e = edges[st / 2]; return if (st % 2 == 0) e.b else e.a }
        // A* bound: straight-line distance to the nearest place a route can really end (the snapped point
        // on a target road, not the off-road destination) at the fastest speed any road can have. 1% slack
        // covers the rounding of stored lengths, so the bound never exceeds the true remaining time.
        val tPts = targets.map { pointOn(edges[it.edge], it.frac) }
        // Road hierarchy: every road within [detailM] of the two ends, only main roads ([isMain]) in
        // between. Keeps a state-length drive in a phone's memory (the side streets of every town on
        // the way are ~80% of the roads). It depends only on the ends, so a warm and a cold graph give
        // the same route.
        val ends = (starts.map { pointOn(edges[it.edge], it.frac) } + tPts).map { (la, lo) -> (la * 1e6).toInt() to (lo * 1e6).toInt() }
        fun endNear(u: Int) = ends.firstOrNull { (la, lo) -> near(lat[u], lon[u], la, lo) <= detailM }
        val vmax = maxOf(MAX_MPH, maxMph) * 0.44704
        fun h(st: Int): Double {
            val n = arrive(st)
            return 0.99 * tPts.minOf { (la, lo) -> meters(lat[n] / 1e6, lon[n] / 1e6, la, lo) } / vmax
        }
        // Search state in primitive arrays (one slot per state reached): boxed maps and queue objects
        // took ~250 MB on a 300-mile drive.
        // Ordinary states are indexed by their directed edge. Only states carrying via-way
        // history need a hash table for the full edge-and-rule key.
        val ordinarySlots = PagedInts()
        val ruleSlots = LongIntMap()
        var nSlots = 0
        val heap = StateHeap(1 shl 14)
        fun push(k: Long, c: Double, from: Int) {
            val state = stOf(k)
            val ordinary = tagOf(k) == 0
            var slot = if (ordinary) ordinarySlots[state] - 1 else ruleSlots[k]
            if (slot < 0) {
                slot = nSlots++
                if (ordinary) ordinarySlots[state] = slot + 1 else ruleSlots[k] = slot
                states.put(slot, k)
            }
            if (c < states.cost[slot]) {
                states.cost[slot] = c
                states.previous[slot] = from
                heap.add(c + h(state), c, slot)
            }
        }
        var best = Double.POSITIVE_INFINITY; var bestSlot = -1; var bestT: Snap? = null
        var direct: Pair<Snap, Snap>? = null
        for (s in starts) for (t in targets) {
            if (s.edge != t.edge) continue
            val e = edges[s.edge]; val c = cost(e)
            if (!c.isFinite() || (e.oneway && t.frac < s.frac)) continue
            val d = c * kotlin.math.abs(t.frac - s.frac) + accessSeconds(s) + accessSeconds(t)
            if (d < best) { best = d; direct = s to t }
        }
        val startOf = HashMap<Int, Snap>()
        for (s in starts) {
            val se = edges[s.edge]; val c0 = cost(se)
            if (!c0.isFinite()) continue
            startOf[s.edge] = s
            push(key(s.edge * 2, 0), c0 * (1 - s.frac) + accessSeconds(s), -1)
            if (!se.oneway) push(key(s.edge * 2 + 1, 0), c0 * s.frac + accessSeconds(s), -1)
        }
        val tCost = targets.map { cost(edges[it.edge]) }
        var expanded = 0
        fun leaves(e: Edge, u: Int) = e.a == u || (e.b == u && !e.oneway)
        while (heap.isNotEmpty()) {
            val qf = heap.topF; val gs = heap.topG; val slot = heap.topSlot
            heap.pop()
            val k = states.keys[slot]
            if (gs > states.cost[slot]) continue      // superseded by a cheaper way here
            if (qf >= best) break
            if (++expanded > 3_000_000) return null
            val st = stOf(k); val tag = tagOf(k)
            val inEdge = edges[st / 2]; val u = arrive(st)
            // Read the junction's cell before judging any exit, the turn onto the target road included:
            // its turn rules live there, so a cold and a warm graph give the same route.
            ensureAt(lat[u], lon[u], full = allRoads)
            val end = if (allRoads) null else endNear(u)
            if (end != null) for ((la, lo) in ends) if (near(lat[u], lon[u], la, lo) <= detailM)
                ensureDetail(Geo.tileX(lon[u] / 1e6, grid), Geo.tileY(lat[u] / 1e6, grid), la / 1e6, lo / 1e6, detailM)
            val detail = allRoads || end != null
            fun allowed(e: Edge) = (detail || isMain(e.cls)) && turnAllowed(inEdge, u, e) && pathAllowed(inEdge.id, tag, u, e.id)
            // Turn onto a target road and stop at the destination.
            for ((ti, t) in targets.withIndex()) {
                val te = edges[t.edge]; val ct = tCost[ti]
                if (!ct.isFinite() || st / 2 == t.edge || !allowed(te)) continue
                val extra = when {
                    u == te.a -> ct * t.frac + turnSeconds(st, t.edge, true)
                    u == te.b && !te.oneway -> ct * (1 - t.frac) + turnSeconds(st, t.edge, false)
                    else -> Double.POSITIVE_INFINITY
                } + accessSeconds(t)
                if (gs + extra < best) { best = gs + extra; bestSlot = slot; bestT = t; direct = null }
            }
            // A U-turn only where nothing else can legally be driven from this arrival: a dead end, or a
            // junction whose other roads are one-way in, blocked, unusable for this vehicle or forbidden turns.
            val otherExit = anyAdjacent(u) { ei ->
                val e = edges[ei]
                ei != st / 2 && leaves(e, u) && cost(e).isFinite() && allowed(e)
            }
            forEachAdjacent(u) { ei ->
                val e = edges[ei]
                if (ei == st / 2 && otherExit) return@forEachAdjacent
                if (!allowed(e)) return@forEachAdjacent
                val c = cost(e); if (c.isInfinite()) return@forEachAdjacent
                val nt = nextTag(tag, inEdge.id, e.id)
                if (e.a == u) push(key(ei * 2, nt), gs + c + turnSeconds(st, ei, true), slot)
                if (e.b == u && !e.oneway && e.a != e.b) push(key(ei * 2 + 1, nt), gs + c + turnSeconds(st, ei, false), slot)
            }
        }
        direct?.let { (s, t) ->
            val e = edges[s.edge]
            if (cross != null && (!usable(e, v) || e.oneway && t.frac < s.frac)) return null
            return build(listOf(s.edge * 2 + if (t.frac >= s.frac) 0 else 1), s, t, v, sameEdge = true)
        }
        if (bestSlot < 0) return null
        val path = ArrayList<Int>()
        var sl = bestSlot
        while (sl >= 0) { path += stOf(states.keys[sl]); sl = states.previous[sl] }
        path.reverse()
        val target = bestT!!
        if (cross != null && !joinedPathAllowed(path, target.edge, v)) return null
        return build(path, startOf.getValue(path.first() / 2), target, v)
    }

    /** A later-loaded alias may add a turn restriction or close a duplicated direction. */
    private fun joinedPathAllowed(path: List<Int>, target: Int, v: Vehicle): Boolean {
        var tag = 0
        for ((i, state) in path.withIndex()) {
            val edge = edges[state / 2]
            val reverse = state % 2 != 0
            if (!usable(edge, v) || reverse && edge.oneway) return false
            val at = if (reverse) edge.a else edge.b
            val next = edges[path.getOrNull(i + 1)?.div(2) ?: target]
            if (!usable(next, v) || at != next.a && (at != next.b || next.oneway)) return false
            if (!turnAllowed(edge, at, next) || !pathAllowed(edge.id, tag, at, next.id)) return false
            tag = nextTag(tag, edge.id, next.id)
        }
        return true
    }

    /** Search weight of reaching a road from the off-road position (only matters when ends have a choice of road). */
    private fun accessSeconds(s: Snap) = s.dist / ACCESS_MPS

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
        val driven = HashMap<Long, Double>()
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
            driven[e.id] = (driven[e.id] ?: 0.0) + e.meters * part
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
        val joined = cross
        val sources = if (joined == null) emptyList() else ids.map(::sourceOf).distinct().map { joined.sources[it - 1] }
        return Route(
            pts, meters, secs, steps, ids.distinct(), s.dist, t.dist, maneuvers(path, s.frac, t, sameEdge, meters, pts), times,
            reducedCoverage = !complete || badCells > 0 || joined?.conflict == true || sources.any { !it.complete },
            driven = driven, packsUsed = if (joined == null) listOfNotNull(sourceName) else sources.map { it.name },
        )
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

    /** Reused between alternatives. Every new search assigns slots from zero and resets their costs. */
    private class SearchStates {
        var keys = LongArray(1 shl 14); private set
        var cost = DoubleArray(keys.size); private set
        var previous = IntArray(keys.size); private set

        fun put(slot: Int, key: Long) {
            if (slot == keys.size) {
                keys = keys.copyOf(slot * 2)
                cost = cost.copyOf(slot * 2)
                previous = previous.copyOf(slot * 2)
            }
            keys[slot] = key
            cost[slot] = Double.POSITIVE_INFINITY
        }
    }

    /** Road IDs are primitive keys. The small lists retain their original insertion order. */
    private class RoadLists<T> {
        private val index = LongIntMap()
        private val values = ArrayList<MutableList<T>>()

        operator fun get(id: Long): MutableList<T>? = values.getOrNull(index[id])

        fun getOrAdd(id: Long): MutableList<T> = get(id) ?: ArrayList<T>(1).also {
            index[id] = values.size
            values += it
        }
    }

    /**
     * Junction adjacency in publication order. Heads and tails are indexed by node, and each edge
     * has one successor slot per end. A link stores the edge index plus one, leaving zero for no link.
     */
    private fun appendAdjacent(node: Int, index: Int) {
        val tail = adjacencyTails[node]
        if (tail == 0) adjacencyHeads[node] = index + 1
        else adjacencyNext[adjacencySlot(node, tail - 1)] = index + 1
        adjacencyTails[node] = index + 1
    }

    private fun adjacencySlot(node: Int, index: Int): Int =
        index * 2 + if (edges[index].a == node) 0 else 1

    private inline fun forEachAdjacent(node: Int, visit: (Int) -> Unit) {
        var link = adjacencyHeads[node]
        while (link != 0) {
            val index = link - 1
            visit(index)
            link = adjacencyNext[adjacencySlot(node, index)]
        }
    }

    private inline fun anyAdjacent(node: Int, matches: (Int) -> Boolean): Boolean {
        forEachAdjacent(node) { if (matches(it)) return true }
        return false
    }

    /** Allocate integer pages on demand. Untouched positions read as zero. */
    private class PagedInts {
        private val pages = ArrayList<IntArray?>()

        operator fun get(index: Int): Int = pages.getOrNull(index ushr PAGE_SHIFT)?.get(index and PAGE_MASK) ?: 0

        operator fun set(index: Int, value: Int) {
            val page = index ushr PAGE_SHIFT
            while (pages.size <= page) pages.add(null)
            val values = pages[page] ?: IntArray(PAGE_SIZE).also { pages[page] = it }
            values[index and PAGE_MASK] = value
        }

        private companion object {
            const val PAGE_SHIFT = 12
            const val PAGE_SIZE = 1 shl PAGE_SHIFT
            const val PAGE_MASK = PAGE_SIZE - 1
        }
    }

    /** A growable list of ints, lighter than List<Int>. */
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
        /** [edgeIndex] value for a road read but left out (no speed). */
        private const val SKIPPED = -2
        private const val LEVEL_MAIN = 1
        private const val LEVEL_FULL = 2
        private const val LOCAL_EDGE_BITS = 48
        private const val LOCAL_EDGE_MASK = (1L shl LOCAL_EDGE_BITS) - 1
        private val EMPTY_SHAPE = IntArray(0)
        /**
         * Every road is searched within this distance of each end of a route (metres): the first that
         * finds a route. In a town a main road is never far; a ranch or trailhead at the end of a long
         * track needs the wider steps.
         */
        val DETAIL_M = doubleArrayOf(8_000.0, 40_000.0, Double.POSITIVE_INFINITY)
        /**
         * Roads searched everywhere: motorway to unclassified (rural county roads are often
         * unclassified), "road" and the link roads. Residential, living street, service and track
         * roads only near the ends.
         */
        fun isMain(cls: Int) = cls <= 5 || cls >= 10
        /** Roads tried at each end when the nearest ones do not connect. */
        private const val SNAP_CANDIDATES = 6
        /** Those other roads are at most this much further off than the nearest one. */
        private const val SNAP_SLACK_M = 250.0
        /** A route shorter than this has the same start and end on the road. */
        private const val ZERO_M = 0.5
        /** Search weight for the off-road distance to a road (m/s, a walk), so a road further off must save that time. */
        private const val ACCESS_MPS = 1.4
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
