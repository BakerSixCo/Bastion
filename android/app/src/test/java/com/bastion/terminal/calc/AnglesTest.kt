package com.bastion.terminal.calc

import com.bastion.terminal.calc.Angles.AngleUnit
import com.bastion.terminal.calc.Angles.Length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reference values are the textbook definitions and the standard mil-relation examples. */
class AnglesTest {
    @Test fun conversions() {
        assertEquals(0.29089, Angles.convert(1.0, AngleUnit.MOA, AngleUnit.MRAD)!!, 1e-5)
        assertEquals(3.43775, Angles.convert(1.0, AngleUnit.MRAD, AngleUnit.MOA)!!, 1e-5)
        assertEquals(1.0472, Angles.convert(1.0, AngleUnit.MOA, AngleUnit.IPHY)!!, 1e-4)
        assertEquals(10.0, Angles.convert(Angles.convert(10.0, AngleUnit.MRAD, AngleUnit.MOA)!!, AngleUnit.MOA, AngleUnit.MRAD)!!, 1e-9)
    }

    @Test fun subtensions() {
        assertEquals(1.047, Angles.subtension(1.0, AngleUnit.MOA, 100.0, Length.YARD, Length.INCH)!!, 1e-3)
        assertEquals(3.6, Angles.subtension(1.0, AngleUnit.MRAD, 100.0, Length.YARD, Length.INCH)!!, 1e-4)
        assertEquals(10.0, Angles.subtension(1.0, AngleUnit.MRAD, 100.0, Length.METER, Length.CM)!!, 1e-4)
        assertEquals(1.0, Angles.subtension(1.0, AngleUnit.IPHY, 100.0, Length.YARD, Length.INCH)!!, 1e-9)
        assertEquals(3.141, Angles.subtension(1.0, AngleUnit.MOA, 300.0, Length.YARD, Length.INCH)!!, 1e-3)
    }

    @Test fun milRelationRange() {
        // 36 in at 2 mil = 500 yd; 1.8 m at 3 mil = 600 m (standard examples).
        assertEquals(500.0, Angles.range(36.0, Length.INCH, 2.0, AngleUnit.MRAD, Length.YARD)!!, 0.01)
        assertEquals(600.0, Angles.range(1.8, Length.METER, 3.0, AngleUnit.MRAD, Length.METER)!!, 0.01)
        // MOA form: size(in) x 95.5 / MOA = yards. 18 in at 6 MOA ~ 286.5 yd.
        assertEquals(286.5, Angles.range(18.0, Length.INCH, 6.0, AngleUnit.MOA, Length.YARD)!!, 0.2)
    }

    @Test fun correctionsAndClicks() {
        val moa = Angles.correction(6.0, Length.INCH, 200.0, Length.YARD, AngleUnit.MOA)!!
        assertEquals(2.865, moa, 1e-3)
        assertEquals(11, Angles.clicks(moa, 0.25))
        val mil = Angles.correction(20.0, Length.CM, 200.0, Length.METER, AngleUnit.MRAD)!!
        assertEquals(1.0, mil, 1e-4)
        assertEquals(10, Angles.clicks(mil, 0.1))
    }

    @Test fun rejectsBadInput() {
        assertNull(Angles.convert(0.0, AngleUnit.MOA, AngleUnit.MRAD))
        assertNull(Angles.convert(-1.0, AngleUnit.MOA, AngleUnit.MRAD))
        assertNull(Angles.convert(Double.NaN, AngleUnit.MOA, AngleUnit.MRAD))
        assertNull(Angles.range(36.0, Length.INCH, 0.0, AngleUnit.MRAD, Length.YARD))
        assertNull(Angles.range(36.0, Length.INCH, 900.0, AngleUnit.MRAD, Length.YARD))
        assertNull(Angles.subtension(1.0, AngleUnit.MOA, 0.0, Length.YARD, Length.INCH))
        assertNull(Angles.correction(6.0, Length.INCH, 0.0, Length.YARD, AngleUnit.MOA))
        assertNull(Angles.clicks(1.0, 0.0))
    }
}
