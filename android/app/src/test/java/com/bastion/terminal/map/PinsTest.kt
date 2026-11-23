package com.bastion.terminal.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinsTest {

    @Test
    fun shareText_andParse_roundTrip() {
        val line = Pins.shareText("Camp", 44.1447, -114.917)
        assertEquals("+Camp 44.14470,-114.91700", line)
        assertEquals(Triple("Camp", 44.1447, -114.917), Pins.parse(line))
        assertEquals(Triple("Water cache 2", 44.1, -115.25), Pins.parse("+ Water cache 2  44.100, -115.250 "))
    }

    @Test
    fun parse_refusesWhatIsNotAPin() {
        assertNull(Pins.parse("Camp 44.14470,-114.91700"))       // no leading +
        assertNull(Pins.parse("+Camp"))                           // no position
        assertNull(Pins.parse("+Camp 144.14470,-114.91700"))      // not a latitude
        assertNull(Pins.parse("+1 for that idea, see 4.5,6.7"))   // chat, not a pin
        assertNull(Pins.parse("?where is camp"))
    }

    @Test
    fun cleanName_dropsSymbolsAndKeepsItShort() {
        assertEquals("Camp", Pins.cleanName(" +Camp\n"))
        assertEquals(24, Pins.cleanName("x".repeat(60)).length)
    }
}
