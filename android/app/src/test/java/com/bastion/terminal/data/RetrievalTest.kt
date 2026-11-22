package com.bastion.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RetrievalTest {

    @Test
    fun chunker_packsParagraphsAndTracksHeadings() {
        val text = "Intro paragraph.\n\n# Water\n\nBoil water for 1 minute.\n\nFilter cloudy water.\n\n## Signals\n\nThree of anything."
        val chunks = Chunker.chunk(text, target = 700, max = 1000)
        assertEquals(3, chunks.size)
        assertNull(chunks[0].heading)
        assertEquals("Intro paragraph.", text.substring(chunks[0].start, chunks[0].end).trim())
        assertEquals("Water", chunks[1].heading)
        // Both "Water" paragraphs share one chunk (under target), heading excluded from body.
        val body = text.substring(chunks[1].start, chunks[1].end)
        assertTrue(body.contains("Boil water") && body.contains("Filter cloudy"))
        assertTrue(!body.contains("# Water"))
        assertEquals("Signals", chunks[2].heading)
    }

    @Test
    fun chunker_respectsTargetAndSplitsOverlongParagraphsAtSentences() {
        val para = (1..40).joinToString(" ") { "Sentence number $it is here." } // ~1,100 chars
        val text = "Short one.\n\n$para"
        val chunks = Chunker.chunk(text, target = 300, max = 400)
        assertTrue(chunks.all { it.end - it.start <= 400 })
        // Every split lands right after a sentence end.
        for (c in chunks.drop(1).dropLast(1)) {
            assertTrue(text.substring(c.start, c.end).trimEnd().endsWith("."))
        }
        // Nothing lost: all sentences appear across chunks.
        val joined = chunks.joinToString(" ") { text.substring(it.start, it.end) }
        assertTrue(joined.contains("Sentence number 1 is") && joined.contains("Sentence number 40 is"))
    }

    @Test
    fun chunker_emptyAndHeadingOnlyTextYieldNothing() {
        assertTrue(Chunker.chunk("").isEmpty())
        assertTrue(Chunker.chunk("# Only a heading\n\n   \n").isEmpty())
    }

    /** Builds a 'pcnalx' matchinfo array for one column. */
    private fun mi(rows: Int, avgLen: Int, len: Int, vararg terms: Triple<Int, Int, Int>): IntArray {
        val out = mutableListOf(terms.size, 1, rows, avgLen, len)
        for ((tf, total, df) in terms) out += listOf(tf, total, df)
        return out.toIntArray()
    }

    @Test
    fun bm25_rareTermOutweighsCommonTerms() {
        val rows = 100
        // Chunk A: one hit on a rare term (in 2 of 100 chunks).
        val rare = Bm25.score(mi(rows, 120, 120, Triple(1, 2, 2), Triple(0, 300, 90)))
        // Chunk B: three hits on a common term (in 90 of 100 chunks).
        val common = Bm25.score(mi(rows, 120, 120, Triple(0, 2, 2), Triple(3, 300, 90)))
        assertTrue("rare=$rare common=$common", rare > common)
        assertEquals(0.0, Bm25.score(mi(rows, 120, 120, Triple(0, 2, 2))), 0.0)
    }

    @Test
    fun scored_coordinationFavorsPassagesMatchingMoreDistinctTerms() {
        // Terms: [radio (6 of 111), schedule (25 of 111)].
        // Filler: "radios" ten times, no "schedule". Answer: radio once + schedule twice.
        val filler = Bm25.scored(1, 1, mi(111, 80, 80, Triple(10, 60, 6), Triple(0, 40, 25)))
        val answer = Bm25.scored(2, 1, mi(111, 80, 80, Triple(1, 60, 6), Triple(2, 40, 25)))
        assertTrue("filler=${filler.score} answer=${answer.score}", answer.score > filler.score)
        assertEquals(listOf(10, 0), filler.termTf.toList())
        assertEquals(listOf(6, 25), answer.termDf.toList())
    }

    @Test
    fun scoreInMemory_matchesPrefixesAndRanksTheRelevantPassageFirst() {
        val items = listOf(
            Bm25.Item(1, 1, "Hypothermia is treated by removing wet clothing and rewarming."),
            Bm25.Item(2, 1, "Heat stroke is treated by rapid cooling."),
            Bm25.Item(3, 2, "Wet clothing should be dried in the sun."),
        )
        val s = Bm25.scoreInMemory(items, listOf("hypotherm", "treat"))
        assertEquals(3, s.size)
        assertEquals(1L, s.maxByOrNull { it.score }!!.id)
        assertEquals(listOf(1, 1), s.first { it.id == 1L }.termTf.toList()) // "treated" matches "treat"
        assertEquals(listOf(1, 2), s.first().termDf.toList())
        assertEquals(0.0, s.first { it.id == 3L }.score, 0.0)
        assertTrue(Bm25.scoreInMemory(emptyList(), listOf("x")).isEmpty())
    }

    @Test
    fun bm25_shorterPassageWinsAtEqualTf() {
        val short = Bm25.score(mi(50, 100, 50, Triple(2, 10, 5)))
        val long = Bm25.score(mi(50, 100, 300, Triple(2, 10, 5)))
        assertTrue(short > long)
    }

    @Test
    fun bm25_decodeReadsNativeOrderInts() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        buf.putInt(3).putInt(1).putInt(42)
        assertEquals(listOf(3, 1, 42), Bm25.decode(buf.array()).toList())
    }

    @Test
    fun select_capsPerDocTotalBudgetAndCutsWeakMatches() {
        val s = listOf(
            Bm25.Scored(1, 10, 9.0), Bm25.Scored(2, 10, 8.0), Bm25.Scored(3, 10, 7.5),
            Bm25.Scored(4, 10, 7.0), // 4th from doc 10: over perDoc=3
            Bm25.Scored(5, 20, 6.0),
            Bm25.Scored(6, 30, 2.0), // below 0.35 × 9.0
        )
        val picked = Bm25.select(s, { 500 }, k = 5, perDoc = 3, charBudget = 3000, relCutoff = 0.35)
        assertEquals(listOf(1L, 2L, 3L, 5L), picked.map { it.id })

        val budgeted = Bm25.select(s, { 1200 }, k = 5, perDoc = 3, charBudget = 3000)
        assertEquals(2, budgeted.size) // 2 × 1200 fits, a 3rd would exceed 3000
        assertTrue(Bm25.select(emptyList(), { 0 }).isEmpty())
    }

    @Test
    fun select_coversRareQueryTermEvenWhenCommonTermsOutscoreIt() {
        // Terms: [bleach (rare: 1 of 100 passages), barrel (common: 40 of 100)].
        val df = intArrayOf(1, 40)
        fun s(id: Long, doc: Long, score: Double, bleach: Int, barrel: Int) =
            Bm25.Scored(id, doc, score, intArrayOf(bleach, barrel), df, rows = 100)
        val candidates = listOf(
            s(1, 1, 7.0, 0, 5), s(2, 2, 6.9, 0, 5), s(3, 3, 6.8, 0, 5),
            s(4, 4, 6.7, 0, 5), s(5, 5, 6.6, 0, 5),
            s(9, 9, 6.5, 2, 0), // the only passage with "bleach", ranked 6th
        )
        val picked = Bm25.select(candidates, { 400 }, k = 5)
        assertEquals(5, picked.size)
        assertTrue(picked.any { it.id == 9L })
        assertTrue(picked.none { it.id == 5L }) // weakest common-term pick made room
    }
}
