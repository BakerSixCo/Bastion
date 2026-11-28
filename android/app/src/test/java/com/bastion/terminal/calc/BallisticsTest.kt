package com.bastion.terminal.calc

import com.bastion.terminal.calc.Ballistics.Air
import com.bastion.terminal.calc.Ballistics.Drag
import com.bastion.terminal.calc.Ballistics.Rifle
import com.bastion.terminal.calc.Ballistics.Wind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values: py_ballisticcalc's own trajectory tests (tests/test_trajectory.py, LGPL-3.0,
 * downloaded 2026-11-25), an independent open-source solver. Tolerances are no looser than theirs.
 */
class BallisticsTest {
    private val yd = 0.9144; private val inch = 0.0254; private val fps = 0.3048
    private val ftlb = 1.3558179; private val mph = 0.44704
    private val moa = Math.PI / 10800 * 1000          // MOA in MRAD

    private fun rifle(drag: Drag, sightIn: Double, twist: Double = 0.0) = Rifle(
        drag = drag, bc = 0.223, muzzleVelocity = 2750 * fps, bulletMassGrains = 168.0,
        sightHeight = sightIn * inch, zeroRange = 100 * yd,
        diameterIn = if (twist > 0) 0.308 else 0.0, lengthIn = if (twist > 0) 1.282 else 0.0, twistIn = twist,
    )

    @Test fun zeroAngles() {
        val g1 = Rifle(Drag.G1, 0.365, 2600 * fps, 69.0, 3.2 * inch, 100 * yd)
        assertEquals(0.0016514, Ballistics.zeroAngle(g1), 1e-4)
        assertEquals(0.0012286, Ballistics.zeroAngle(rifle(Drag.G7, 2.0)), 1e-4)
    }

    @Test fun g1PathMatchesReference() {
        val rows = Ballistics.solve(
            rifle(Drag.G1, 2.0), Air.STANDARD, Wind(5 * mph, 4.5),
            listOf(100 * yd, 500 * yd, 1000 * yd), bore = 0.001228,
        )!!
        val (r100, r500, r1000) = rows
        assertEquals(2351.2, r100.velocity / fps, 5.0)
        assertEquals(0.0, r100.path / inch, 0.5)
        assertEquals(0.118, r100.time, 0.06)
        assertEquals(1169.1, r500.velocity / fps, 5.0)
        assertEquals(509.8, r500.energyJ / ftlb, 5.0)
        assertEquals(-87.9, r500.path / inch, 1.0)
        assertEquals(16.8, r500.elevationMrad / moa, 0.5)
        assertEquals(-19.5, r500.windage / inch, 1.0)
        assertEquals(0.857, r500.time, 0.06)
        assertEquals(776.4, r1000.velocity / fps, 5.0)
        assertEquals(-823.9, r1000.path / inch, 4.0)
        assertEquals(78.7, r1000.elevationMrad / moa, 0.5)
        assertEquals(-87.5, r1000.windage / inch, 1.5)
        assertEquals(2.495, r1000.time, 0.06)
    }

    @Test fun g7PathWithSpinDriftMatchesReference() {
        val rows = Ballistics.solve(
            rifle(Drag.G7, 2.0, twist = 12.0), Air.STANDARD, Wind(5 * mph, 4.5),
            listOf(100 * yd, 500 * yd, 1000 * yd), bore = 4.221 * moa / 1000,
        )!!
        val (r100, r500, r1000) = rows
        assertEquals(2545.0, r100.velocity / fps, 5.0)
        assertEquals(0.0, r100.path / inch, 0.5)
        assertEquals(1814.0, r500.velocity / fps, 5.0)
        assertEquals(1227.0, r500.energyJ / ftlb, 5.0)
        assertEquals(-56.2, r500.path / inch, 1.0)
        assertEquals(3.2, r500.elevationMrad, 0.5 * 1.0)
        assertEquals(-6.3, r500.windage / inch, 1.0)
        assertEquals(0.672, r500.time, 0.06)
        assertEquals(1086.0, r1000.velocity / fps, 5.0)
        assertEquals(-399.9, r1000.path / inch, 4.0)
        assertEquals(11.3, r1000.elevationMrad, 0.5)
        assertEquals(-31.6, r1000.windage / inch, 1.5)
        assertEquals(1.748, r1000.time, 0.06)
    }

    @Test fun thinnerAirShootsFlatter() {
        val r = rifle(Drag.G7, 2.0)
        val sea = Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), listOf(500 * yd))!![0]
        val high = Ballistics.solve(r, Air.atAltitude(2500.0, 15.0), Wind(0.0, 0.0), listOf(500 * yd))!![0]
        assertTrue(high.path > sea.path)
        assertEquals(0.0, Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), listOf(100 * yd))!![0].path, 0.001)
    }

    @Test fun windFromLeftPushesRightAndHeadWindNoDrift() {
        val r = rifle(Drag.G7, 2.0)
        val left = Ballistics.solve(r, Air.STANDARD, Wind(10 * mph, 9.0), listOf(300 * yd))!![0]
        assertTrue(left.windage > 0); assertTrue(left.windageMrad < 0)
        val head = Ballistics.solve(r, Air.STANDARD, Wind(10 * mph, 12.0), listOf(300 * yd))!![0]
        assertEquals(0.0, head.windage, 1e-6)
    }

    @Test fun uphillHitsHighComparedWithFlat() {
        val r = rifle(Drag.G7, 2.0)
        val flat = Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), listOf(400 * yd))!![0]
        val up = Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), listOf(400 * yd), lookAngle = Math.toRadians(30.0))!![0]
        assertTrue(up.path > flat.path)
    }

    @Test fun rejectsBadInput() {
        val r = rifle(Drag.G7, 2.0)
        assertNull(Ballistics.solve(r.copy(bc = 0.0), Air.STANDARD, Wind(0.0, 0.0), listOf(100.0)))
        assertNull(Ballistics.solve(r, Air(5000.0, 15.0), Wind(0.0, 0.0), listOf(100.0)))
        assertNull(Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), listOf(-5.0)))
        assertNull(Ballistics.solve(r, Air.STANDARD, Wind(0.0, 0.0), emptyList()))
    }
}
