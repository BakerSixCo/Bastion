package com.bastion.terminal.calc

import com.bastion.terminal.calc.WaterBleach.Strength
import com.bastion.terminal.calc.WaterBleach.Unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every EPA table row must come out exactly as printed (EPA 2026, CDC 2024 in the kit). */
class WaterBleachTest {
    private fun drops(a: Double, u: Unit, s: Strength, cloudy: Boolean = false) =
        WaterBleach.dose(a, u, s, cloudy)!!.doses.associate { it.agency to it.drops }

    @Test fun epaTableRowsSixPercent() {
        assertEquals(mapOf("EPA and CDC" to 2), drops(1.0, Unit.QUART, Strength.SIX))
        assertEquals(mapOf("EPA and CDC" to 2), drops(1.0, Unit.LITER, Strength.SIX))
        assertEquals(mapOf("EPA and CDC" to 8), drops(1.0, Unit.GALLON, Strength.SIX))
        assertEquals(mapOf("EPA and CDC" to 16), drops(2.0, Unit.GALLON, Strength.SIX))
    }

    @Test fun eightPercentShowsBothAgenciesLargerFirst() {
        assertEquals(mapOf("EPA and CDC" to 2), drops(1.0, Unit.QUART, Strength.EIGHT_25))
        val r = WaterBleach.dose(1.0, Unit.GALLON, Strength.EIGHT_25, false)!!
        assertEquals("CDC", r.doses[0].agency); assertEquals(8, r.doses[0].drops)
        assertEquals("EPA", r.doses[1].agency); assertEquals(6, r.doses[1].drops)
        assertEquals(12, drops(2.0, Unit.GALLON, Strength.EIGHT_25)["EPA"])
        assertTrue(r.notes.any { "differ" in it })
    }

    @Test fun cloudyDoubles() {
        assertEquals(16, drops(1.0, Unit.GALLON, Strength.SIX, cloudy = true)["EPA and CDC"])
        assertEquals(4, drops(1.0, Unit.QUART, Strength.SIX, cloudy = true)["EPA and CDC"])
    }

    @Test fun tableRowQuotedVerbatim() {
        val r = WaterBleach.dose(4.0, Unit.GALLON, Strength.EIGHT_25, false)!!
        assertEquals("EPA table, 4 gallons: 1/4 teaspoon of 8.25% bleach", r.tableRow)
        assertEquals("EPA table, 1 liter: 2 drops of 6% bleach", WaterBleach.dose(1.0, Unit.LITER, Strength.SIX, false)!!.tableRow)
        assertNull(WaterBleach.dose(3.0, Unit.GALLON, Strength.SIX, false)!!.tableRow)
        assertNull(WaterBleach.dose(4.0, Unit.LITER, Strength.SIX, false)!!.tableRow)
    }

    @Test fun otherVolumesRoundUpNeverDown() {
        assertEquals(9, drops(4.0, Unit.LITER, Strength.SIX)["EPA and CDC"])     // 1.057 gal x 8 = 8.45
        assertEquals(2, drops(0.5, Unit.QUART, Strength.SIX)["EPA and CDC"])     // never under 2 drops
        assertEquals(40, drops(5.0, Unit.GALLON, Strength.SIX)["EPA and CDC"])
        assertEquals(6, drops(3.0, Unit.QUART, Strength.SIX)["EPA and CDC"])
    }

    @Test fun badInputGivesNothing() {
        assertNull(WaterBleach.dose(0.0, Unit.GALLON, Strength.SIX, false))
        assertNull(WaterBleach.dose(-1.0, Unit.GALLON, Strength.SIX, false))
        assertNull(WaterBleach.dose(Double.NaN, Unit.GALLON, Strength.SIX, false))
        assertNull(WaterBleach.dose(5000.0, Unit.GALLON, Strength.SIX, false))
    }
}
