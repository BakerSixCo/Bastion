package com.bastion.terminal.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshBridgeTest {

    @Test
    fun fit_shortTextIsCleanedButNotShortened() {
        val (t, cut) = MeshBridge.fit("Rally at the north water tower [1].\n\n  Bring water [2, 3].")
        assertEquals("Rally at the north water tower.\nBring water.", t)
        assertFalse(cut)
    }

    @Test
    fun fit_longTextCutAtWordWithinByteLimit() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val (t, cut) = MeshBridge.fit(long)
        assertTrue(cut)
        assertTrue(MeshBridge.byteLength(t) <= MeshBridge.MAX_BYTES)
        assertTrue(t.endsWith("…"))
        assertTrue("ends on a whole word", Regex("word[0-9]+$").containsMatchIn(t.dropLast(1)))
    }

    @Test
    fun fit_neverSplitsMultiByteCharacters() {
        val emoji = "🚑".repeat(100) // 4 bytes each
        val (t, cut) = MeshBridge.fit(emoji)
        assertTrue(cut)
        assertTrue(MeshBridge.byteLength(t) <= MeshBridge.MAX_BYTES)
        // Round-trips cleanly: no broken surrogate pair.
        assertEquals(t, String(t.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertFalse(t.contains('�'))
        val accents = "é".repeat(300)
        assertTrue(MeshBridge.byteLength(MeshBridge.fit(accents).first) <= MeshBridge.MAX_BYTES)
    }

    @Test
    fun split_numberedStepsBecomeNumberedMessagesWithinTheLimit() {
        val steps = (1..6).joinToString("\n") { "$it. Step number $it tells the person to do one clear practical thing right now [1]." }
        val parts = MeshBridge.split(steps, 4) { i, n -> if (n == 1) "AI: " else "AI $i/$n: " }
        assertTrue(parts.size in 2..4)
        parts.forEachIndexed { i, p ->
            assertTrue("part ${i + 1} within the limit", MeshBridge.byteLength(p) <= MeshBridge.MAX_BYTES)
            assertTrue(p.startsWith("AI ${i + 1}/${parts.size}: "))
            assertFalse(p.contains("[1]"))
        }
        // Nothing lost and order kept when it all fits.
        val joined = parts.joinToString(" ") { it.substringAfter(": ") }
        assertTrue(joined.indexOf("1. Step") < joined.indexOf("6. Step"))
        // A short answer stays one plain message.
        assertEquals(listOf("AI: Boil water for one minute."), MeshBridge.split("Boil water for one minute. [2]", 4) { i, n -> if (n == 1) "AI: " else "AI $i/$n: " })
        // Too long for the part limit: capped, last part ends with an ellipsis.
        val long = (1..40).joinToString("\n") { "$it. " + "word ".repeat(12).trim() + "." }
        val capped = MeshBridge.split(long, 3) { i, n -> "AI $i/$n: " }
        assertEquals(3, capped.size)
        assertTrue(capped.last().endsWith("…"))
        assertTrue(capped.all { MeshBridge.byteLength(it) <= MeshBridge.MAX_BYTES })
    }
}
