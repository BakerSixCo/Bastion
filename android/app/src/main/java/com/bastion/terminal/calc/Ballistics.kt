package com.bastion.terminal.calc

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Point-mass ballistic solver (G1/G7 drag functions, air density from pressure, temperature and
 * humidity, wind, uphill/downhill angle, optional Litz spin drift). Pure arithmetic — the AI model
 * never produces a holdover; it may only fill in these inputs.
 *
 * Internally SI; the frame is the line of sight: x along it, y perpendicular up, z to the right.
 * Not modelled (said on screen): Coriolis, aerodynamic jump, wind that differs along the path.
 */
object Ballistics {
    enum class Drag(val table: List<Pair<Double, Double>>) { G1(DragTables.G1), G7(DragTables.G7) }

    data class Rifle(
        val drag: Drag,
        val bc: Double,                 // lb/in², for the chosen drag function
        val muzzleVelocity: Double,     // m/s
        val bulletMassGrains: Double,
        val sightHeight: Double,        // m, centre of scope above centre of bore
        val zeroRange: Double,          // m
        val diameterIn: Double = 0.0,   // spin drift needs diameter, length and twist; 0 = off
        val lengthIn: Double = 0.0,
        val twistIn: Double = 0.0,      // inches per turn, right-hand
    )

    data class Air(val pressurePa: Double, val tempC: Double, val humidity: Double = 0.0) {
        val tempK get() = tempC + 273.15
        val density: Double get() {
            val es = 610.78 * exp(17.27 * tempC / (tempC + 237.3))
            val pv = humidity.coerceIn(0.0, 1.0) * es
            return (pressurePa - pv) / (287.058 * tempK) + pv / (461.495 * tempK)
        }
        val speedOfSound: Double get() = sqrt(1.4 * 287.058 * tempK)

        companion object {
            /** ICAO standard atmosphere at sea level: 15 °C, 1013.25 hPa, dry. */
            val STANDARD = Air(101325.0, 15.0, 0.0)
            /** Station pressure estimated from altitude (standard atmosphere), for phones with no barometer. */
            fun atAltitude(altitudeM: Double, tempC: Double, humidity: Double = 0.0) =
                Air(101325.0 * (1 - 2.25577e-5 * altitudeM).pow(5.25588), tempC, humidity)
        }
    }

    /** Wind speed in m/s; [clock] is where it blows FROM: 12 = into your face, 3 = from the right, 9 = from the left. */
    data class Wind(val speed: Double, val clock: Double)

    data class Row(
        val range: Double,       // m along the line of sight
        val path: Double,        // m above (+) or below (-) the line of sight
        val windage: Double,     // m right (+) or left (-)
        val velocity: Double,    // m/s
        val energyJ: Double,
        val time: Double,        // s
    ) {
        /** Scope correction in MRAD: positive = dial up / right. */
        val elevationMrad get() = if (range <= 0) 0.0 else -atan(path / range) * 1000
        val windageMrad get() = if (range <= 0) 0.0 else -atan(windage / range) * 1000
    }

    private const val G = 9.80665
    private const val GRAIN_KG = 6.479891e-5
    private const val BC_SI = 703.0696           // 1 lb/in² in kg/m²

    private fun cd(drag: Drag, mach: Double): Double {
        val t = drag.table
        if (mach <= t.first().first) return t.first().second
        if (mach >= t.last().first) return t.last().second
        var i = 1
        while (t[i].first < mach) i++
        val (m0, c0) = t[i - 1]; val (m1, c1) = t[i]
        return c0 + (c1 - c0) * (mach - m0) / (m1 - m0)
    }

    /**
     * Integrates one shot fired at [bore] radians above the line of sight, which is itself tilted
     * [lookAngle] radians (uphill +). Reports a row at each of [ranges] (m, ascending).
     */
    private fun fly(r: Rifle, air: Air, wind: Wind, lookAngle: Double, bore: Double, ranges: List<Double>): List<Row> {
        val k = air.density * PI / (8 * r.bc * BC_SI)
        val c = air.speedOfSound
        val mass = r.bulletMassGrains * GRAIN_KG
        val gx = -G * sin(lookAngle); val gy = -G * cos(lookAngle)
        val phi = wind.clock * PI / 6
        // Air velocity: a 12 o'clock wind moves toward the shooter (-x), a 3 o'clock wind moves left (-z).
        val wx = -wind.speed * cos(phi); val wz = -wind.speed * sin(phi)
        var x = 0.0; var y = -r.sightHeight; var z = 0.0
        var vx = r.muzzleVelocity * cos(bore); var vy = r.muzzleVelocity * sin(bore); var vz = 0.0
        var t = 0.0
        val dt = 0.0005
        val out = ArrayList<Row>()
        var next = 0
        fun acc(vx: Double, vy: Double, vz: Double): DoubleArray {
            val rx = vx - wx; val rz = vz - wz
            val v = sqrt(rx * rx + vy * vy + rz * rz)
            val a = k * cd(r.drag, v / c) * v
            return doubleArrayOf(-a * rx + gx, -a * vy + gy, -a * rz)
        }
        while (next < ranges.size && t < 10.0) {
            if (ranges[next] <= x) {
                out += Row(ranges[next], y, z, sqrt(vx * vx + vy * vy + vz * vz), 0.5 * mass * (vx * vx + vy * vy + vz * vz), t)
                next++; continue
            }
            val px = x; val py = y; val pz = z; val pvx = vx; val pvy = vy; val pvz = vz
            // RK4 on (position, velocity).
            val a1 = acc(vx, vy, vz)
            val a2 = acc(vx + a1[0] * dt / 2, vy + a1[1] * dt / 2, vz + a1[2] * dt / 2)
            val a3 = acc(vx + a2[0] * dt / 2, vy + a2[1] * dt / 2, vz + a2[2] * dt / 2)
            val a4 = acc(vx + a3[0] * dt, vy + a3[1] * dt, vz + a3[2] * dt)
            fun dv(i: Int) = dt / 6 * (a1[i] + 2 * a2[i] + 2 * a3[i] + a4[i])
            fun dp(v: Double, i: Int) = dt * v + dt * dt / 6 * (a1[i] + a2[i] + a3[i])
            x += dp(vx, 0); y += dp(vy, 1); z += dp(vz, 2)
            vx += dv(0); vy += dv(1); vz += dv(2)
            t += dt
            // Rows that fall inside this step are interpolated linearly.
            while (next < ranges.size && ranges[next] <= x) {
                val f = (ranges[next] - px) / (x - px)
                fun l(a: Double, b: Double) = a + (b - a) * f
                val v = sqrt(l(pvx, vx).pow(2) + l(pvy, vy).pow(2) + l(pvz, vz).pow(2))
                out += Row(ranges[next], l(py, y), l(pz, z), v, 0.5 * mass * v * v, t - dt + dt * f)
                next++
            }
            if (vx <= 0) break
        }
        return out
    }

    /** Bore angle (radians above the line of sight) that puts the bullet on the line of sight at the zero range. */
    fun zeroAngle(r: Rifle, zeroAir: Air = Air.STANDARD): Double {
        var lo = -0.01; var hi = 0.05
        repeat(60) {
            val mid = (lo + hi) / 2
            val p = fly(r, zeroAir, Wind(0.0, 0.0), 0.0, mid, listOf(r.zeroRange)).firstOrNull()?.path ?: -1.0
            if (p < 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Miller stability factor, corrected for velocity and air (needs diameter, length and twist). */
    fun stability(r: Rifle, air: Air): Double? {
        if (r.diameterIn <= 0 || r.lengthIn <= 0 || r.twistIn <= 0) return null
        val tCal = r.twistIn / r.diameterIn; val lCal = r.lengthIn / r.diameterIn
        val sg = 30 * r.bulletMassGrains / (tCal * tCal * r.diameterIn.pow(3) * lCal * (1 + lCal * lCal))
        val fps = r.muzzleVelocity / 0.3048
        val tempF = air.tempC * 9 / 5 + 32
        val inHg = air.pressurePa / 3386.389
        return sg * (fps / 2800).pow(1.0 / 3) * (tempF + 460) / (59 + 460) * 29.92 / inHg
    }

    /**
     * Solves a shot. [zeroAir] is the air the rifle was zeroed in (standard air if unknown);
     * [air] is the air now. Spin drift (Litz) is added to windage when the rifle has twist data.
     */
    fun solve(
        r: Rifle, air: Air, wind: Wind, ranges: List<Double>,
        lookAngle: Double = 0.0, zeroAir: Air = Air.STANDARD,
        bore: Double? = null,           // tests only: a fixed bore angle instead of solving the zero
    ): List<Row>? {
        if (!(r.bc > 0 && r.muzzleVelocity > 0 && r.bulletMassGrains > 0 && r.zeroRange > 0 && r.sightHeight >= 0)) return null
        if (!(air.pressurePa in 30000.0..110000.0 && air.tempC in -60.0..60.0)) return null
        if (ranges.isEmpty() || ranges.any { it <= 0 || it > 3000 }) return null
        val rows = fly(r, air, wind, lookAngle, bore ?: zeroAngle(r, zeroAir), ranges.sorted())
        val sg = stability(r, air) ?: return rows
        return rows.map { row -> row.copy(windage = row.windage + 1.25 * (sg + 1.2) * row.time.pow(1.83) * 0.0254) }
    }
}
