package com.bastion.terminal.calc

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/**
 * Angle math for scopes and rangefinding: MOA, MRAD (mil) and inches-per-hundred-yards.
 * Pure arithmetic from the unit definitions — never produced by the AI model.
 *
 *  - 1 MRAD = 0.001 radian (1 m at 1000 m, 3.6 in at 100 yd).
 *  - 1 MOA  = 1/60 degree (about 1.047 in at 100 yd; about 0.2909 MRAD).
 *  - 1 IPHY ("shooter's MOA") = 1.000 in at 100 yd, used by some scopes.
 */
object Angles {
    enum class AngleUnit(val label: String, val radians: Double, val clickDefault: Double) {
        MOA("MOA", PI / 10800.0, 0.25),
        MRAD("MRAD", 0.001, 0.1),
        IPHY("IPHY", atan(1.0 / 3600.0), 0.25),
    }

    enum class Length(val label: String, val meters: Double) {
        INCH("in", 0.0254), CM("cm", 0.01), FOOT("ft", 0.3048), YARD("yd", 0.9144), METER("m", 1.0),
    }

    /** Largest angle accepted: 0.5 rad (about 1719 MOA). Beyond that the input is almost surely a typo. */
    private const val MAX_RAD = 0.5

    private fun ok(x: Double) = x.isFinite() && x > 0.0

    /** Converts an angle between units; null for zero, negative or absurd input. */
    fun convert(value: Double, from: AngleUnit, to: AngleUnit): Double? {
        if (!ok(value) || value * from.radians > MAX_RAD) return null
        return value * from.radians / to.radians
    }

    /** Size an angle covers at a distance (e.g. 1 MOA at 300 yd, in inches). */
    fun subtension(angle: Double, unit: AngleUnit, distance: Double, distUnit: Length, sizeUnit: Length): Double? {
        val rad = angle * unit.radians
        if (!ok(angle) || !ok(distance) || rad > MAX_RAD) return null
        return distance * distUnit.meters * tan(rad) / sizeUnit.meters
    }

    /**
     * Range from a known size and its measured angle in the reticle (the mil relation).
     * Example: a 36 in object reading 2 MRAD is 500 yd away.
     */
    fun range(size: Double, sizeUnit: Length, angle: Double, unit: AngleUnit, outUnit: Length): Double? {
        val rad = angle * unit.radians
        if (!ok(size) || !ok(angle) || rad > MAX_RAD) return null
        return size * sizeUnit.meters / tan(rad) / outUnit.meters
    }

    /** Angle needed to move the point of impact by [offset] at [distance]. */
    fun correction(offset: Double, offsetUnit: Length, distance: Double, distUnit: Length, unit: AngleUnit): Double? {
        if (!ok(offset) || !ok(distance)) return null
        val rad = atan(offset * offsetUnit.meters / (distance * distUnit.meters))
        if (rad > MAX_RAD) return null
        return rad / unit.radians
    }

    /** Whole scope clicks for an angle, rounded to the nearest click. */
    fun clicks(angle: Double, clickValue: Double): Int? =
        if (!ok(clickValue) || !angle.isFinite() || angle < 0) null else Math.round(angle / clickValue).toInt()
}
