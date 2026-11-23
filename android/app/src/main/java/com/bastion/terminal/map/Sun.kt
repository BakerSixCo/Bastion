package com.bastion.terminal.map

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, worked out from the date, time and position (the
 * low-precision formulas of the Astronomical Almanac, good to a small
 * fraction of a degree for decades either side of 2000). Pure JVM.
 */
object Sun {

    /** [azimuth]: degrees from north, clockwise. [elevation]: degrees above the horizon (negative = down). */
    class Position(val azimuth: Double, val elevation: Double)

    fun position(epochMillis: Long, lat: Double, lon: Double): Position {
        val d = epochMillis / 86_400_000.0 + 2440587.5 - 2451545.0 // days since noon, 1 Jan 2000 UTC
        val g = Math.toRadians((357.529 + 0.98560028 * d).mod(360.0))
        val q = (280.459 + 0.98564736 * d).mod(360.0)
        val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)).mod(360.0))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstHours = (18.697374558 + 24.06570982441908 * d).mod(24.0)
        val ha = Math.toRadians(gmstHours * 15 + lon) - ra
        val phi = Math.toRadians(lat)
        val el = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha))
        val az = atan2(-sin(ha), tan(dec) * cos(phi) - sin(phi) * cos(ha))
        return Position(Math.toDegrees(az).mod(360.0), Math.toDegrees(el))
    }
}
