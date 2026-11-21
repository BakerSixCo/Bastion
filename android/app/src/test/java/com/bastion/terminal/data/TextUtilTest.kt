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
    fun formatSize_usesDecimalUnitsAndNeverShowsZeroForSmallFiles() {
        assertEquals("0 B", TextUtil.formatSize(0))
        assertEquals("52 B", TextUtil.formatSize(52))
        assertEquals("999 B", TextUtil.formatSize(999))
        assertEquals("1.2 KB", TextUtil.formatSize(1_234))
        assertEquals("20.0 MB", TextUtil.formatSize(20_000_000))
        assertEquals("21.0 MB", TextUtil.formatSize(20_971_000))
        assertEquals("1.5 GB", TextUtil.formatSize(1_500_000_000))
    }

    @Test
    fun readBounded_returnsBytesAtLimitAndNullPastIt() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertTrue(data.contentEquals(TextUtil.readBounded(data.inputStream(), 200_000)))
        assertNull(TextUtil.readBounded(data.inputStream(), 199_999))
        assertEquals(0, TextUtil.readBounded(ByteArray(0).inputStream(), 10)!!.size)
    }

    @Test
    fun highlightRanges_emptyQueryYieldsNothing() {
        assertTrue(TextUtil.highlightRanges("some text", " ").isEmpty())
        assertTrue(TextUtil.highlightRanges("some text", "a").isEmpty())
    }
}
