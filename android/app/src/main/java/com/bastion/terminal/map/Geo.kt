package com.bastion.terminal.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web-map tile arithmetic (the "slippy map" scheme every tile source here
 * uses). Positions are also held as **world fractions**: x and y from 0 to 1
 * across the whole Mercator world, independent of zoom. Pure JVM.
 */
object Geo {
    const val TILE = 256

    /** Ground distance in metres between two positions (good to well under 1% at these ranges). */
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return kotlin.math.sqrt(x * x + y * y) * EARTH_CIRCUMFERENCE_M / (2 * PI)
    }
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** Longitude → world fraction 0..1. */
    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    /** Latitude → world fraction 0..1 (0 = north edge). */
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    fun lon(x: Double): Double = x * 360.0 - 180.0

    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /** Tiles (or pixels, times [TILE]) across the world at [zoom]. */
    fun tilesAcross(zoom: Int): Int = 1 shl zoom

    fun tileX(lon: Double, zoom: Int): Int = floor(x(lon) * tilesAcross(zoom)).toInt()

    fun tileY(lat: Double, zoom: Int): Int = floor(y(lat) * tilesAcross(zoom)).toInt()

    /** Ground metres covered by one tile pixel at [lat] and [zoom]. */
    fun metersPerPixel(lat: Double, zoom: Int): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (TILE * 2.0.pow(zoom))

    /** Elevation in metres from one "terrarium" pixel (red, green, blue 0..255). */
    fun terrarium(r: Int, g: Int, b: Int): Float = r * 256f + g + b / 256f - 32768f

    /** Tobler's hiking function: walking speed in m/s for a slope of [rise] over [run] metres. */
    fun walkSpeed(rise: Double, run: Double): Double {
        if (run <= 0.0) return 0.0
        return 6.0 * exp(-3.5 * kotlin.math.abs(rise / run + 0.05)) / 3.6
    }
}
