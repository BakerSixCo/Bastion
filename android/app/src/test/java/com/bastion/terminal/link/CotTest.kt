package com.bastion.terminal.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotTest {
    // Captured from Meshtastic's local TAK server (simulated node), 2026-11-22.
    private val contact = "<event version='2.0' uid='MESHTASTIC-CC00000B' type='a-f-G-U-C' time='2026-11-22T19:03:00.898Z' " +
        "start='2026-11-22T19:03:00.898Z' stale='2026-11-22T19:18:00.898Z' how='m-g'><point lat='39.7672448' " +
        "lon='-104.988672' hae='9999999.0' ce='9999999.0' le='9999999.0'/><detail><contact endpoint='*:-1:stcp' " +
        "callsign='SIMB - BASTION Sim B'/><uid Droid='SIMB - BASTION Sim B'/><__group role='Team Member' name='Green'/>" +
        "<status battery='100'/><track course='0.0' speed='0.0'/></detail></event>"

    @Test
    fun parse_contactFromMeshNode() {
        val c = (Cot.parse(contact, now = 5L) as Cot.Event.Contact).contact
        assertEquals("MESHTASTIC-CC00000B", c.uid)
        assertEquals("SIMB - BASTION Sim B", c.callsign)
        assertEquals(39.7672448, c.lat, 1e-9)
        assertEquals(-104.988672, c.lon, 1e-9)
        assertEquals(100, c.battery)
        assertEquals("Green", c.team)
        assertTrue(c.isMeshNode && c.hasPosition)
    }

    @Test
    fun parse_controlTrafficAndGarbageAreIgnored() {
        val pong = "<event version=\"2.0\" uid=\"takPong\" type=\"t-x-d-d\" time=\"x\" start=\"x\" stale=\"x\" how=\"m-g\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999.0\" le=\"9999999.0\"/><detail/></event>"
        assertEquals(Cot.Event.Other, Cot.parse(pong))
        assertEquals(Cot.Event.Other, Cot.parse("not xml at all"))
        // Out-of-range coordinates are not a position.
        assertEquals(Cot.Event.Other, Cot.parse(contact.replace("lat='39.7672448'", "lat='539.0'")))
    }

    @Test
    fun chat_roundTripsAndEscapesText() {
        val xml = Cot.chat("BASTION-ABCD1234", "BASTION", "Rally <north> & \"wait\"", now = 0L)
        assertTrue(xml.contains("type=\"b-t-f\""))
        assertTrue(xml.contains("Rally &lt;north&gt; &amp; &quot;wait&quot;"))
        val chat = (Cot.parse(xml, now = 9L) as Cot.Event.Chat).chat
        assertEquals("BASTION", chat.sender)
        assertEquals("Rally <north> & \"wait\"", chat.text)
        assertEquals("All Chat Rooms", chat.room)
    }

    @Test
    fun distance_isRoughlyRight() {
        // Sim A (39.7392, -104.9903) to Sim B (39.75, -105.0): about 1.46 km.
        val d = Cot.distanceMeters(39.7392, -104.9903, 39.75, -105.0)
        assertTrue(d > 1300 && d < 1600)
    }
}
