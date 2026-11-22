package com.bastion.terminal.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** A span of a document's text, with the nearest preceding Markdown heading. */
data class Chunk(val start: Int, val end: Int, val heading: String?)

/**
 * Splits text into retrieval passages. Pure JVM (unit-tested).
 *
 * Paragraph-aligned: paragraphs (blank-line separated) are packed into chunks
 * of about [target] characters; a paragraph longer than [max] is split at
 * sentence ends, and a sentence longer than [max] at a space. Headings
 * (`# …`) start a new chunk and are remembered as the chunk's context.
 */
object Chunker {

    fun chunk(text: String, target: Int = 700, max: Int = 1000): List<Chunk> {
        val out = ArrayList<Chunk>()
        var heading: String? = null
        var curStart = -1
        var curEnd = -1
        var curHeading: String? = null

        fun flush() {
            if (curStart >= 0 && text.substring(curStart, curEnd).isNotBlank()) {
                out.add(Chunk(curStart, curEnd, curHeading))
            }
            curStart = -1
        }

        fun add(start: Int, end: Int) {
            if (curStart >= 0 && (end - curStart) > target) flush()
            if (curStart < 0) {
                curStart = start
                curHeading = heading
            }
            curEnd = end
        }

        for ((pStart, pEnd) in paragraphs(text)) {
            val para = text.substring(pStart, pEnd)
            val headingMatch = HEADING.matchEntire(para.trim())
            if (headingMatch != null) {
                flush()
                heading = headingMatch.groupValues[1].trim()
                continue
            }
            if (pEnd - pStart <= max) {
                add(pStart, pEnd)
            } else {
                for ((s, e) in splitLong(text, pStart, pEnd, max)) add(s, e)
            }
        }
        flush()
        return out
    }

    private val HEADING = Regex("#{1,6}\\s+(.+)")

    /** [start, end) of each non-blank paragraph. */
    private fun paragraphs(text: String): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        val sep = Regex("\\r?\\n\\s*\\r?\\n")
        var pos = 0
        for (m in sep.findAll(text)) {
            if (m.range.first > pos) res.add(pos to m.range.first)
            pos = m.range.last + 1
        }
        if (pos < text.length) res.add(pos to text.length)
        return res.filter { (s, e) -> text.substring(s, e).isNotBlank() }
    }

    /** Splits [start, end) into pieces ≤ max at sentence ends, else at spaces. */
    private fun splitLong(text: String, start: Int, end: Int, max: Int): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var s = start
        while (end - s > max) {
            val window = text.substring(s, s + max)
            var cut = Regex("[.!?][\"')\\]]?\\s").findAll(window).lastOrNull()?.range?.last?.plus(1) ?: -1
            if (cut < max / 3) cut = window.lastIndexOf(' ').let { if (it < max / 3) max else it + 1 }
            res.add(s to s + cut)
            s += cut
        }
        if (s < end) res.add(s to end)
        return res
    }
}

/**
 * Okapi BM25 over SQLite FTS4 `matchinfo(t, 'pcnalx')` output. Pure JVM.
 * Each quoted query term is one "phrase"; the FTS table has one column.
 */
object Bm25 {
    private const val K1 = 1.2
    private const val B = 0.75

    /** Decodes a matchinfo BLOB (native-order 32-bit unsigned ints). */
    fun decode(blob: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder())
        return IntArray(blob.size / 4) { buf.int }
    }

    /**
     * Score from matchinfo 'pcnalx': [p, c, n, a(c), l(c), x(3*p*c)].
     * Column 0 is scored. IDF uses the BM25+1 form so it is never negative.
     */
    fun score(mi: IntArray): Double {
        val p = mi[0]
        val c = mi[1]
        val n = mi[2].toDouble()
        val avgLen = mi[3].toDouble().coerceAtLeast(1.0)
        val len = mi[3 + c].toDouble()
        val xBase = 3 + 2 * c
        var s = 0.0
        for (i in 0 until p) {
            val x = xBase + 3 * (i * c + 0)
            val tf = mi[x].toDouble()
            if (tf <= 0) continue
            val df = mi[x + 2].toDouble()
            val idf = ln((n - df + 0.5) / (df + 0.5) + 1.0)
            s += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * len / avgLen))
        }
        return s
    }

    /**
     * A scored passage. [termTf] = hits of each query term in this passage;
     * [termDf] = passages (of [rows]) containing each term — same for all rows.
     */
    data class Scored(
        val id: Long,
        val docId: Long,
        val score: Double,
        val termTf: IntArray = IntArray(0),
        val termDf: IntArray = IntArray(0),
        val rows: Int = 0,
    )

    /**
     * Builds a [Scored] from a decoded 'pcnalx' matchinfo array. The BM25
     * score is scaled by a coordination factor (distinct query terms matched
     * ÷ query terms, as in classic Lucene), so a passage matching "radio" AND
     * "schedule" beats one repeating only "radio".
     */
    fun scored(id: Long, docId: Long, mi: IntArray): Scored {
        val p = mi[0]
        val c = mi[1]
        val xBase = 3 + 2 * c
        val tf = IntArray(p) { mi[xBase + 3 * (it * c)] }
        val coord = if (p == 0) 0.0 else tf.count { it > 0 }.toDouble() / p
        return Scored(
            id = id,
            docId = docId,
            score = score(mi) * coord,
            termTf = tf,
            termDf = IntArray(p) { mi[xBase + 3 * (it * c) + 2] },
            rows = mi[2],
        )
    }

    /** A passage scored in memory (not from SQLite), e.g. ZIM article text. */
    data class Item(val id: Long, val docId: Long, val text: String)

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /**
     * Scores [items] against prefix [terms] exactly like the SQLite path:
     * builds the same 'pcnalx' layout (prefix matching on lowercase words,
     * df and average length over [items]) and feeds it to [scored].
     */
    fun scoreInMemory(items: List<Item>, terms: List<String>): List<Scored> {
        if (items.isEmpty() || terms.isEmpty()) return emptyList()
        val words = items.map { WORD.findAll(it.text.lowercase()).map { m -> m.value }.toList() }
        val tf = words.map { ws -> IntArray(terms.size) { t -> ws.count { it.startsWith(terms[t]) } } }
        val df = IntArray(terms.size) { t -> tf.count { it[t] > 0 } }
        val total = IntArray(terms.size) { t -> tf.sumOf { it[t] } }
        val avg = (words.sumOf { it.size } / items.size).coerceAtLeast(1)
        return items.indices.map { i ->
            val mi = IntArray(5 + 3 * terms.size)
            mi[0] = terms.size; mi[1] = 1; mi[2] = items.size; mi[3] = avg; mi[4] = words[i].size
            for (t in terms.indices) {
                mi[5 + 3 * t] = tf[i][t]; mi[6 + 3 * t] = total[t]; mi[7 + 3 * t] = df[t]
            }
            scored(items[i].id, items[i].docId, mi)
        }
    }

    /**
     * Picks passages: best first, at most [perDoc] per document, at most [k]
     * total, within [charBudget] (via [lengthOf]), dropping anything below
     * [relCutoff] × the best score (keeps the prompt focused).
     *
     * Then **query-term coverage**: every discriminative term (in at most
     * [rareFraction] of passages) that some candidate contains must be covered
     * by a chosen passage; if not, its best passage replaces the weakest pick.
     * A rare word in a question is usually its point ("bleach" among many
     * "water barrel" documents).
     */
    fun select(
        scored: List<Scored>,
        lengthOf: (Long) -> Int,
        k: Int = 5,
        perDoc: Int = 3,
        charBudget: Int = 3000,
        relCutoff: Double = 0.35,
        rareFraction: Double = 0.2,
    ): List<Scored> {
        val sorted = scored.sortedByDescending { it.score }
        val best = sorted.firstOrNull()?.score ?: return emptyList()
        val perDocCount = HashMap<Long, Int>()
        val out = ArrayList<Scored>()
        var chars = 0
        for (sc in sorted) {
            if (out.size >= k) break
            if (sc.score < best * relCutoff) break
            if ((perDocCount[sc.docId] ?: 0) >= perDoc) continue
            val len = lengthOf(sc.id)
            if (out.isNotEmpty() && chars + len > charBudget) continue
            out.add(sc)
            chars += len
            perDocCount[sc.docId] = (perDocCount[sc.docId] ?: 0) + 1
        }

        // Query-term coverage for discriminative terms.
        val sample = sorted.first()
        val nTerms = sample.termTf.size
        val pinned = HashSet<Long>()
        for (t in 0 until nTerms) {
            val df = sample.termDf.getOrElse(t) { 0 }
            if (df == 0 || sample.rows == 0 || df.toDouble() / sample.rows > rareFraction) continue
            if (out.any { it.termTf[t] > 0 }) {
                out.filter { it.termTf[t] > 0 }.maxByOrNull { it.score }?.let { pinned += it.id }
                continue
            }
            val cover = sorted.firstOrNull { it.termTf[t] > 0 } ?: continue
            out.add(cover)
            pinned += cover.id
            chars += lengthOf(cover.id)
            // Make room: drop the weakest unpinned picks until within k and budget.
            while (out.size > k || (chars > charBudget && out.size > 1)) {
                val victim = out.filter { it.id !in pinned }.minByOrNull { it.score } ?: break
                out.remove(victim)
                chars -= lengthOf(victim.id)
            }
        }
        return out.sortedByDescending { it.score }
    }
}
