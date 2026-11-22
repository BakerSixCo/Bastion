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
}
