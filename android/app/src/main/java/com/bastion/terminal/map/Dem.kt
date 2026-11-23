package com.bastion.terminal.map

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A block of elevation: [w] × [h] cells cut from the elevation tiles at
 * [zoom], with its top-left cell at world pixel ([x0], [y0]). Steepness,
 * shading and terrain routes are all computed from this on the phone — no
 * pre-rendered overlay is needed in the pack. Pure JVM.
 */
class Dem(val zoom: Int, val x0: Int, val y0: Int, val w: Int, val h: Int, val el: FloatArray) {

    init { require(el.size == w * h) { "elevation size" } }

    private val worldPx = Geo.TILE.toDouble() * Geo.tilesAcross(zoom)

    fun at(px: Int, py: Int): Float = el[py.coerceIn(0, h - 1) * w + px.coerceIn(0, w - 1)]

    fun lat(py: Double): Double = Geo.lat((y0 + py) / worldPx)
    fun lon(px: Double): Double = Geo.lon((x0 + px) / worldPx)

    /** Cell column (fractional) for a longitude; may be outside 0..w. */
    fun px(lon: Double): Double = Geo.x(lon) * worldPx - x0
    fun py(lat: Double): Double = Geo.y(lat) * worldPx - y0

    fun contains(lat: Double, lon: Double): Boolean {
        val x = px(lon); val y = py(lat)
        return x >= 0 && y >= 0 && x < w && y < h
    }

    /** Ground size of one cell at row [py], metres. */
    fun cellMeters(py: Int): Double = Geo.metersPerPixel(lat(py + 0.5), zoom)

    /** Elevation in metres at a position (bilinear), or null outside the block. */
    fun elevation(lat: Double, lon: Double): Float? {
        if (!contains(lat, lon)) return null
        val fx = (px(lon) - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (py(lat) - 0.5).coerceIn(0.0, h - 1.0)
        val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
        val tx = (fx - ix).toFloat(); val ty = (fy - iy).toFloat()
        val a = at(ix, iy) * (1 - tx) + at(ix + 1, iy) * tx
        val b = at(ix, iy + 1) * (1 - tx) + at(ix + 1, iy + 1) * tx
        return a * (1 - ty) + b * ty
    }

    /** Steepness of the ground at a cell, degrees (0 = flat). */
    fun slopeDeg(px: Int, py: Int): Float {
        val m = cellMeters(py)
        val dzdx = (at(px + 1, py) - at(px - 1, py)) / (2 * m)
        val dzdy = (at(px, py + 1) - at(px, py - 1)) / (2 * m)
        return Math.toDegrees(atan(sqrt(dzdx * dzdx + dzdy * dzdy))).toFloat()
    }

    /**
     * Shaded relief 0..1 (1 = fully lit) with the light from the north-west,
     * 45° above the horizon — the convention printed maps use.
     */
    fun hillshade(px: Int, py: Int): Float {
        val m = cellMeters(py)
        val dzdx = (at(px + 1, py) - at(px - 1, py)) / (2 * m)
        val dzdy = (at(px, py + 1) - at(px, py - 1)) / (2 * m)
        // Surface normal (-dzdx, dzdy [north is -y], 1) · light direction.
        val az = Math.toRadians(315.0); val alt = Math.toRadians(45.0)
        val lx = sin(az) * cos(alt); val ly = cos(az) * cos(alt); val lz = sin(alt)
        val nx = -dzdx; val ny = dzdy; val nz = 1.0
        val d = (nx * lx + ny * ly + nz * lz) / sqrt(nx * nx + ny * ny + nz * nz)
        return d.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The way the slope at a cell faces (the downhill direction), degrees from
     * north clockwise; null where the ground is close to flat (under 2°).
     */
    fun aspectDeg(px: Int, py: Int): Float? {
        val m = cellMeters(py)
        val dzdx = (at(px + 1, py) - at(px - 1, py)) / (2 * m) // rise towards the east
        val dzdy = (at(px, py + 1) - at(px, py - 1)) / (2 * m) // rise towards the south
        if (sqrt(dzdx * dzdx + dzdy * dzdy) < 0.035) return null
        // Downhill points west where it rises to the east, north where it rises to the south.
        return ((Math.toDegrees(kotlin.math.atan2(-dzdx, dzdy)) + 360) % 360).toFloat()
    }

    /**
     * Whether a cell is in shade with the sun at [azimuthDeg] (from north,
     * clockwise) and [elevationDeg] above the horizon: the slope faces away
     * from the sun, or higher ground towards the sun stands in the way
     * (searched out to [reachCells] cells, within this block).
     */
    fun shaded(px: Int, py: Int, azimuthDeg: Double, elevationDeg: Double, reachCells: Int = 160): Boolean {
        if (elevationDeg <= 0) return true
        val m = cellMeters(py)
        val az = Math.toRadians(azimuthDeg); val alt = Math.toRadians(elevationDeg)
        val dzdx = (at(px + 1, py) - at(px - 1, py)) / (2 * m)
        val dzdy = (at(px, py + 1) - at(px, py - 1)) / (2 * m)
        // Surface normal (-dzdx east, dzdy north, 1 up) against the direction to the sun.
        if (-dzdx * sin(az) * cos(alt) + dzdy * cos(az) * cos(alt) + sin(alt) <= 0) return true
        val sx = sin(az); val sy = -cos(az) // one cell towards the sun (north is up the rows)
        val risePerCell = kotlin.math.tan(alt) * m
        val base = at(px, py) + 2f // eye height of the ground itself, so a cell does not shade itself
        var s = 1
        while (s <= reachCells) {
            val x = (px + sx * s).roundToInt(); val y = (py + sy * s).roundToInt()
            if (x < 0 || y < 0 || x >= w || y >= h) return false
            if (el[y * w + x] > base + risePerCell * s) return true
            s += if (s < 24) 1 else 2
        }
        return false
    }

    companion object {
        /**
         * Steepness classes, as shown in the legend. Below 10° nothing is drawn.
         * Colours are ARGB.
         */
        val SLOPE_CLASSES: List<Triple<Float, Int, String>> = listOf(
            Triple(10f, 0x00000000, "under 10° — easy"),
            Triple(15f, 0x99FFFF00.toInt(), "10–15° — steady climb"),
            Triple(20f, 0x99FFBE00.toInt(), "15–20° — steep"),
            Triple(30f, 0x99FD7A1E.toInt(), "20–30° — very steep"),
            Triple(40f, 0xAAE0301E.toInt(), "30–40° — hands needed"),
            Triple(Float.MAX_VALUE, 0xBB6A1B9A.toInt(), "over 40° — cliff-like"),
        )

        fun slopeColor(deg: Float): Int = SLOPE_CLASSES.first { deg < it.first }.second
    }
}
