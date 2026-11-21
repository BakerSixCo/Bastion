package com.bastion.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {

    @Test
    fun binaryDetection_rejectsNulBytes_acceptsText() {
        assertTrue(TextUtil.isLikelyBinary(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01)))
        assertFalse(TextUtil.isLikelyBinary("plain text\nwith lines, even éü unicode".toByteArray()))
        assertFalse(TextUtil.isLikelyBinary(ByteArray(0)))
    }

    @Test
    fun ftsQuery_quotesTermsAndStripsEmbeddedQuotes() {
        assertEquals("\"water*\" \"purification*\"", TextUtil.ftsQuery("water purification"))
        assertEquals("\"droptable*\"", TextUtil.ftsQuery(" \"drop\"\"table\" "))
        assertNull(TextUtil.ftsQuery("   "))
        assertNull(TextUtil.ftsQuery("\"\""))
    }

    @Test
    fun highlightRanges_findsCaseInsensitiveOccurrences() {
        val r = TextUtil.highlightRanges("Water, water everywhere", "water")
        assertEquals(2, r.size)
        assertEquals(0, r[0].first)
        assertEquals(7, r[1].first)
    }

    @Test
    fun highlightRanges_emptyQueryYieldsNothing() {
        assertTrue(TextUtil.highlightRanges("some text", " ").isEmpty())
        assertTrue(TextUtil.highlightRanges("some text", "a").isEmpty())
    }
}
