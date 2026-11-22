package com.bastion.terminal.ai

import android.content.Context
import com.bastion.terminal.data.Bm25
import com.bastion.terminal.data.Chunker
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary

/**
 * One retrieval front door for Ask: the user's library plus the offline
 * reference ZIM (if one is open), ranked together.
 *
 * - Library: [LibraryStore.retrieve] (chunked BM25, already evaluated).
 * - ZIM: the ZIM's own full-text index picks the top articles; their text is
 *   chunked with the same [Chunker].
 * - The union is re-scored with the same BM25 × coordination + term-coverage
 *   selection ([Bm25.scoreInMemory] / [Bm25.select]), so no source is
 *   favoured by construction; at most 3 passages per document/article.
 *
 * With no ZIM open, this returns the library passages unchanged.
 */
class Retriever(context: Context) {
    private val store = LibraryStore.get(context)
    private val zim = ZimLibrary.get(context)

    /** Diagnostics for the last call (shown in eval logs, never document text). */
    var lastZimArticles: List<String> = emptyList()
        private set

    /** Diagnostics: chosen passages with scores and per-term hits (no document text). */
    var lastChoice: String = ""
        private set

    suspend fun retrieve(question: String): List<Passage> {
        val terms = Grounding.keyTerms(question)
        if (terms.isEmpty()) return emptyList()
        val lib = store.retrieve(question)
        val zimInfo = zim.currentInfo()
        val hits = if (zimInfo != null) zim.search(terms, MAX_ARTICLES) else emptyList()
        lastZimArticles = hits.map { "${it.title} (${it.score}%)" }
        if (hits.isEmpty()) return lib

        data class Cand(val title: String, val heading: String?, val text: String, val docId: Long, val zimPath: String?)
        val cands = ArrayList<Cand>()
        lib.forEach { cands += Cand(it.title, it.heading, it.plainText, it.docId, null) }
        hits.forEachIndexed { i, h ->
            val article = zim.article(h.path) ?: return@forEachIndexed
            val text = article.text
            for (ch in Chunker.chunk(text)) {
                val body = text.substring(ch.start, ch.end).trim()
                if (body.length < MIN_ZIM_CHARS) continue
                // Negative ids keep ZIM articles distinct from library doc ids for the per-doc cap.
                cands += Cand("${zimInfo!!.title}: ${article.title}", ch.heading, body, -(i + 1L), article.path)
            }
        }
        // Score each passage with its title and heading ("Snakebite — Treatment"): a section
        // rarely repeats the subject its article title already names (seen on emulator:
        // without this, "Epidemiology" sections outranked "Snakebite › Treatment").
        val scored = Bm25.scoreInMemory(
            cands.mapIndexed { i, c -> Bm25.Item(i.toLong(), c.docId, "${c.title}\n${c.heading.orEmpty()}\n${c.text}") },
            terms,
        )
        // Title prior: the article *about* the subject should win ("Hypothermia" over
        // "Shapiro syndrome" for hypothermia symptoms — seen on emulator). Up to +50%,
        // proportional to the share of key terms found in the passage's title.
        val boosted = scored.map { sc ->
            val titleWords = Regex("[\\p{L}\\p{N}]+").findAll(cands[sc.id.toInt()].title.lowercase()).map { it.value }.toList()
            val inTitle = terms.count { t -> titleWords.any { it.startsWith(t) } }
            sc.copy(score = sc.score * (1.0 + TITLE_BOOST * inTitle / terms.size))
        }
        val chosen = Bm25.select(boosted, { cands[it.toInt()].text.length })
        lastChoice = chosen.joinToString(" ; ") { sc ->
            val c = cands[sc.id.toInt()]
            "${c.title.takeLast(28)}|${c.heading?.take(20)} s=${"%.2f".format(sc.score)} tf=${sc.termTf.toList()}"
        } + " | terms=$terms df=${scored.firstOrNull()?.termDf?.toList()} n=${scored.size}"
        return chosen.mapIndexed { n, sc ->
            val c = cands[sc.id.toInt()]
            Passage(
                index = n + 1,
                docId = c.docId,
                title = c.title,
                markedText = Grounding.markTerms(c.text, terms),
                heading = c.heading,
                zimPath = c.zimPath,
            )
        }
    }

    private companion object {
        const val MAX_ARTICLES = 5
        /** Skip stub fragments (lone captions, one-word lines). */
        const val MIN_ZIM_CHARS = 80
        const val TITLE_BOOST = 0.5
    }
}
