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
    ) {
        val arrived: Boolean get() = remaining < 30
    }

    private val pts = route.points
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + RoadGraph.meters(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
    }
    private val length = cum.lastOrNull() ?: 0.0
    private var lastAlong = 0.0

    /** Where [lat], [lon] is on the route. Prefers the part just ahead of the last position, so a road that doubles back is not confused with itself. */
    fun update(lat: Double, lon: Double): State {
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
        val (off, along) = if (nearBest < best + 25) nearBest to nearAlong else best to bestAlong
        lastAlong = along
        val next = route.maneuvers.firstOrNull { it.atMeters > along + 5 } ?: route.maneuvers.last()
        val remaining = (length - along).coerceAtLeast(0.0)
        val secs = if (length > 0) route.seconds * remaining / length else 0.0
        return State(along, off, next, (next.atMeters - along).coerceAtLeast(0.0), remaining, secs)
    }

    /** The position [ahead] metres further along the route from [along] (for "blocked ahead"). */
    fun pointAhead(along: Double, ahead: Double): Pair<Double, Double> {
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
