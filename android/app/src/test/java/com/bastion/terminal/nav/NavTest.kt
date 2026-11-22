package com.bastion.terminal.nav

import com.bastion.terminal.link.TakContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTest {
    private fun c(call: String, lat: Double, lon: Double, seen: Long = 0L) =
        TakContact("MESHTASTIC-$call", call, "a-f-G-U-C", lat, lon, 100, "Green", null, seen)

    @Test
    fun bearing_cardinalDirectionsAndCompassNames() {
        assertEquals(0, Nav.bearingDeg(39.0, -105.0, 40.0, -105.0))
        assertEquals(180, Nav.bearingDeg(40.0, -105.0, 39.0, -105.0))
        assertEquals(90, Nav.bearingDeg(0.0, 0.0, 0.0, 1.0))
        assertEquals(270, Nav.bearingDeg(0.0, 1.0, 0.0, 0.0))
        assertEquals("N", Nav.compass(0)); assertEquals("N", Nav.compass(359)); assertEquals("NE", Nav.compass(45))
        assertEquals("E", Nav.compass(90)); assertEquals("S", Nav.compass(180)); assertEquals("NNW", Nav.compass(337))
    }

    @Test
    fun legText_distanceAndBearingBetweenTheSimulatedNodes() {
        // Sim A (39.7392, -104.9903) -> Sim B (39.7500, -105.0000): ~1.5 km to the north-west.
        val leg = Nav.legText(Nav.Fix(39.7392, -104.9903), c("SIMB", 39.75, -105.0))!!
        assertTrue(leg, leg.startsWith("1.5 km at 3") && leg.endsWith("NW"))
        assertNull(Nav.legText(null, c("SIMB", 39.75, -105.0)))
        assertEquals("at your position", Nav.legText(Nav.Fix(39.75, -105.0), c("SIMB", 39.75, -105.0)))
        assertEquals("850 m", Nav.distanceText(847.0))
        assertEquals("12 km", Nav.distanceText(12_400.0))
    }

    @Test
    fun targetOf_recognisesNavigationQuestionsOnly() {
        assertEquals("bravo", Nav.targetOf("where is bravo?"))
        assertEquals("Sim B", Nav.targetOf("nav to Sim B"))
        assertEquals("medic", Nav.targetOf("distance to the medic"))
        assertEquals("camp", Nav.targetOf("guide me to camp"))
        assertNull(Nav.targetOf("how do I treat a burn"))
        assertNull(Nav.targetOf("what is the gate code at camp"))
    }

    @Test
    fun answer_isComputedAndHonestAboutUnknowns() {
        val contacts = listOf(c("SIMB - BASTION Sim B", 39.75, -105.0, seen = 1_000L), c("MEDIC - Dana", 0.0, 0.0))
        val a = Nav.answer("sim b", contacts, Nav.Fix(39.7392, -104.9903), "you", now = 181_000L)
        assertTrue(a, a.startsWith("SIMB: 1.5 km at 3") && a.contains("from you") && a.contains("39.75000, -105.00000") && a.endsWith("seen 3 min ago."))
        assertEquals("MEDIC - Dana: no position reported.", Nav.answer("medic", contacts, null, "BASTION", 0L))
        assertTrue(Nav.answer("zulu", contacts, null, "BASTION", 0L).startsWith("No position known for \"zulu\". Known: SIMB"))
        // Without our own position: coordinates only, no invented distance.
        assertTrue(!Nav.answer("sim b", contacts, null, "BASTION", 2_000L).contains(" km"))
    }
}
