package com.bastion.terminal.map

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Turn-by-turn guidance along a computed route: where the GPS position is on the route, the next
 * instruction, how far to it, what is left, and whether the driver has left the route. Pure logic
 * (no Android), so it is unit-tested; every number comes from the route and the GPS, never the AI.
 */
class Guidance(val route: RoadGraph.Route) {

    data class State(
        val along: Double,              // metres along the route
        val offRoute: Double,           // metres from the route line
        val next: RoadGraph.Maneuver,   // the next instruction (ARRIVE at the end)
        val toNext: Double,             // metres to it
        val remaining: Double,          // metres to the destination
        val remainingSeconds: Double,
        /** True only on a dependable fix at the end of the route: within [ARRIVE_M] of its last point, within [ARRIVE_LATERAL_M] of the line, GPS accuracy under [ARRIVE_ACCURACY_M]. */
        val arrived: Boolean = false,
    )

    private val pts = route.points
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + RoadGraph.meters(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
    }
    private val length = cum.lastOrNull() ?: 0.0
    private var lastAlong = 0.0
    private var started = false
    /** Where the last fix matched outside the window, if that match was not believed yet. */
    private var pendingJump: Double? = null

    /** Planned seconds from the start to each point (from the route, or by distance share for old routes). */
    private val times: DoubleArray =
        if (route.pointSeconds.size == pts.size) route.pointSeconds.toDoubleArray()
        else DoubleArray(pts.size) { i -> if (length > 0) route.seconds * cum[i] / length else 0.0 }

    /** Planned seconds from the start to [along] metres along the route. */
    private fun secondsAt(along: Double): Double {
        if (pts.size < 2) return 0.0
        var i = 1
        while (i < pts.size - 1 && cum[i] < along) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg > 0) ((along - cum[i - 1]) / seg).coerceIn(0.0, 1.0) else 1.0
        return times[i - 1] + (times[i] - times[i - 1]) * f
    }

    /**
     * Where [lat], [lon] is on the route. Prefers the part just ahead of the last position, so a road
     * that doubles back is not confused with itself. [accuracyM] is the GPS accuracy; arrival needs it.
     */
    fun update(lat: Double, lon: Double, accuracyM: Float? = null): State {
        var best = Double.MAX_VALUE; var bestAlong = lastAlong
        var nearBest = Double.MAX_VALUE; var nearAlong = lastAlong
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_574.0
        for (i in 1 until pts.size) {
            val ax = (pts[i - 1].second - lon) * kx; val ay = (pts[i - 1].first - lat) * ky
            val bx = (pts[i].second - lon) * kx; val by = (pts[i].first - lat) * ky
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            val t = if (l2 == 0.0) 0.0 else (-(ax * vx + ay * vy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * vx; val py = ay + t * vy
            val d = sqrt(px * px + py * py)
            val a = cum[i - 1] + t * (cum[i] - cum[i - 1])
            if (d < best) { best = d; bestAlong = a }
            // Within 400 m behind/ahead of where we were: the likely match.
            if (a in lastAlong - 100..lastAlong + 400 && d < nearBest) { nearBest = d; nearAlong = a }
        }
        // A route of one point (start and end at the same spot): the distance to that point is the match.
        if (pts.size == 1) { best = RoadGraph.meters(lat, lon, pts[0].first, pts[0].second); nearBest = best; bestAlong = 0.0; nearAlong = 0.0 }
        // A much closer match outside the window (the other pass of a switchback, say) is believed only on
        // sustained evidence: the first fix of the drive, or a second fix in a row that agrees with it.
        // One noisy fix must not move the driver onto the return pass and give its directions.
        val jump = nearBest >= best + 25
        val (off, along) = when {
            !jump -> nearBest to nearAlong
            !started -> best to bestAlong
            pendingJump?.let { kotlin.math.abs(it - bestAlong) <= JUMP_AGREE_M } == true -> best to bestAlong
            else -> nearBest to nearAlong
        }
        pendingJump = if (jump && along != bestAlong) bestAlong else null
        started = true
        lastAlong = along
        val next = route.maneuvers.firstOrNull { it.atMeters > along + 5 } ?: route.maneuvers.last()
        val remaining = (length - along).coerceAtLeast(0.0)
        // Time left is the planned time of the part still ahead (a slow last stretch keeps its time).
        val secs = (route.seconds - secondsAt(along)).coerceAtLeast(0.0)
        // Arrival only on a dependable fix at the end itself: a single fix on a parallel road can project
        // past the end of the line, and a poor fix can be anywhere.
        val end = pts.lastOrNull()
        val arrived = end != null && accuracyM != null && accuracyM < ARRIVE_ACCURACY_M && off <= ARRIVE_LATERAL_M &&
            RoadGraph.meters(lat, lon, end.first, end.second) <= ARRIVE_M
        return State(along, off, next, (next.atMeters - along).coerceAtLeast(0.0), remaining, secs, arrived)
    }

    /** The position [ahead] metres further along the route from [along] (for "blocked ahead"). */
    fun pointAhead(along: Double, ahead: Double): Pair<Double, Double> {
        if (pts.size < 2) return pts.first()   // a one-point route: there is nothing further along
        val target = (along + ahead).coerceAtMost(length)
        var i = 1
        while (i < pts.size - 1 && cum[i] < target) i++
        val seg = (cum[i] - cum[i - 1]).takeIf { it > 0 } ?: 1.0
        val f = ((target - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
        return pts[i - 1].first + (pts[i].first - pts[i - 1].first) * f to pts[i - 1].second + (pts[i].second - pts[i - 1].second) * f
    }

    /**
     * What to say now, if anything: each instruction is spoken once far out, once close, and once
     * at the turn. Distances grow with speed so there is time to react on a highway.
     */
    private val spoken = HashSet<String>()
    fun announcement(s: State, mph: Double): String? {
        if (s.arrived) return if (spoken.add("arrive")) "You have arrived." else null
        // Off the route, the next turn on it is not the driver's next turn: say nothing until back on it.
        if (s.offRoute > OFF_ROUTE_M) return null
        val far = if (mph > 45) 1600.0 else 500.0
        val near = if (mph > 45) 400.0 else 150.0
        val key = "${s.next.atMeters.toInt()}"
        val text = instruction(s.next)
        return when {
            s.toNext <= 40 && spoken.add("$key-now") -> text + "."
            s.toNext in 40.0..near && spoken.add("$key-near") -> "In ${distanceWords(s.toNext)}, ${text.replaceFirstChar { it.lowercase() }}."
            s.toNext in near..far && spoken.add("$key-far") -> "In ${distanceWords(s.toNext)}, ${text.replaceFirstChar { it.lowercase() }}."
            else -> null
        }
    }

    companion object {
        const val ARRIVE_M = 40.0
        const val ARRIVE_LATERAL_M = 50.0
        const val ARRIVE_ACCURACY_M = 50f
        /** Further than this from the route line counts as off the route. */
        const val OFF_ROUTE_M = 60.0
        /** Two fixes in a row matching outside the window within this many metres of each other: believe the jump. */
        const val JUMP_AGREE_M = 150.0
        /** A GPS fix older than this is not used to start, follow or end navigation. */
        const val MAX_FIX_AGE_MS = 30_000L

        /** True if a fix taken at [fixMs] is recent enough at [nowMs] (same clock) to navigate by; unknown time is not. */
        fun fresh(fixMs: Long?, nowMs: Long): Boolean = fixMs != null && nowMs - fixMs in -5_000L..MAX_FIX_AGE_MS

        fun instruction(m: RoadGraph.Maneuver): String {
            val onto = m.onto.takeIf { it.isNotBlank() }
            return when (m.turn) {
                RoadGraph.Turn.DEPART -> "Head out" + (onto?.let { " on $it" } ?: "")
                RoadGraph.Turn.CONTINUE -> "Continue" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.SLIGHT_LEFT -> "Bear left" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.LEFT -> "Turn left" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.SHARP_LEFT -> "Sharp left" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.SLIGHT_RIGHT -> "Bear right" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.RIGHT -> "Turn right" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.SHARP_RIGHT -> "Sharp right" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.U_TURN -> "Make a U-turn" + (onto?.let { " onto $it" } ?: "")
                RoadGraph.Turn.ARRIVE -> "Arrive at the destination"
            }
        }

        /** A glyph for the banner. */
        fun arrow(t: RoadGraph.Turn): String = when (t) {
            RoadGraph.Turn.DEPART, RoadGraph.Turn.CONTINUE -> "↑"
            RoadGraph.Turn.SLIGHT_LEFT -> "↖"; RoadGraph.Turn.LEFT -> "←"; RoadGraph.Turn.SHARP_LEFT -> "↙"
            RoadGraph.Turn.SLIGHT_RIGHT -> "↗"; RoadGraph.Turn.RIGHT -> "→"; RoadGraph.Turn.SHARP_RIGHT -> "↘"
            RoadGraph.Turn.U_TURN -> "↶"; RoadGraph.Turn.ARRIVE -> "◎"
        }

        /** Spoken distance, rounded the way people say it. */
        fun distanceWords(m: Double): String {
            val ft = m * 3.28084
            return when {
                ft < 1000 -> "${((ft / 100).toInt().coerceAtLeast(1)) * 100} feet"
                m < 1609.344 * 0.4 -> "a quarter mile"
                m < 1609.344 * 0.75 -> "half a mile"
                m < 1609.344 * 1.5 -> "1 mile"
                else -> "${(m / 1609.344).toInt()} miles"
            }
        }
    }
}
