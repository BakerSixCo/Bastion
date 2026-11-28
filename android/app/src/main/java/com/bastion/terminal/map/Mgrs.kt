package com.bastion.terminal.map

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Military grid (MGRS / USNG) positions, worked out on the phone: the grid
 * read off a paper topo map and spoken over a radio ("11T PJ 6645 8935").
 * WGS84, standard UTM zones — right for the United States; the special zones
 * around Norway and Svalbard and the polar caps are not handled. Pure JVM.
 */
object Mgrs {

    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private val E2 = F * (2 - F)
    private val EP2 = E2 / (1 - E2)

    class Utm(val zone: Int, val easting: Double, val northing: Double, val north: Boolean)

    fun zone(lon: Double): Int = (floor((lon + 180) / 6).toInt() % 60 + 60) % 60 + 1

    private fun centralMeridian(zone: Int): Double = Math.toRadians((zone - 1) * 6 - 180 + 3.0)

    private fun meridianArc(phi: Double): Double =
        A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256) * phi -
            (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2.pow(3) / 1024) * sin(2 * phi) +
            (15 * E2 * E2 / 256 + 45 * E2.pow(3) / 1024) * sin(4 * phi) -
            (35 * E2.pow(3) / 3072) * sin(6 * phi))

    /** [zone]: force a zone (to draw one zone's grid across its edge); default is the position's own. */
    fun toUtm(lat: Double, lon: Double, zone: Int = zone(lon)): Utm {
        val phi = Math.toRadians(lat)
        val n = A / sqrt(1 - E2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = EP2 * cos(phi).pow(2)
        val a = (Math.toRadians(lon) - centralMeridian(zone)) * cos(phi)
        val x = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120) + 500000.0
        var y = K0 * (meridianArc(phi) + n * tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720))
        if (lat < 0) y += 10_000_000.0
        return Utm(zone, x, y, lat >= 0)
    }

    /** Latitude and longitude of a UTM position. */
    fun toLatLon(zone: Int, easting: Double, northing: Double, north: Boolean = true): Pair<Double, Double> {
        val y = if (north) northing else northing - 10_000_000.0
        val mu = y / K0 / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val p1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(p1).pow(2))
        val t1 = tan(p1).pow(2)
        val c1 = EP2 * cos(p1).pow(2)
        val r1 = A * (1 - E2) / (1 - E2 * sin(p1).pow(2)).pow(1.5)
        val d = (easting - 500000.0) / (n1 * K0)
        val phi = p1 - (n1 * tan(p1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
            (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lam = centralMeridian(zone) + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
            (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(p1)
        return Math.toDegrees(phi) to Math.toDegrees(lam)
    }

    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val COLUMNS = arrayOf("STUVWXYZ", "ABCDEFGH", "JKLMNPQR") // by zone % 3
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    fun band(lat: Double): Char = BANDS[(floor((lat + 80) / 8).toInt()).coerceIn(0, BANDS.length - 1)]

    /** The two letters of the 100 km square a UTM position is in. */
    fun square(u: Utm): String {
        val col = COLUMNS[u.zone % 3][((floor(u.easting / 100000).toInt() - 1) % 8 + 8) % 8]
        val row = ROWS[((floor(u.northing / 100000).toInt() + if (u.zone % 2 == 0) 5 else 0) % 20 + 20) % 20]
        return "$col$row"
    }

    /**
     * "11T PJ 6645 8935". [digits]: figures per coordinate — 4 is the nearest
     * 10 m (an "8-figure grid"), 3 the nearest 100 m, 5 the nearest metre.
     * Figures are cut, not rounded, as the grid convention requires. Empty
     * outside 80°S..84°N.
     */
    fun format(lat: Double, lon: Double, digits: Int = 4): String {
        if (lat < -80 || lat > 84) return ""
        val u = toUtm(lat, lon)
        val div = 10.0.pow(5 - digits)
        val e = floor((u.easting % 100000) / div).toInt()
        val n = floor((u.northing % 100000) / div).toInt()
        if (digits <= 0) return String.format(Locale.US, "%d%c %s", u.zone, band(lat), square(u))
        return String.format(Locale.US, "%d%c %s %0${digits}d %0${digits}d", u.zone, band(lat), square(u), e, n)
    }
}
