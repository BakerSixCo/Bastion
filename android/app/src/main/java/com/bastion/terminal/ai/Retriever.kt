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
class Retriever internal constructor(
    private val store: LibraryStore,
    private val zim: ZimLibrary,
) {
    constructor(context: Context) : this(LibraryStore.get(context), ZimLibrary.get(context))

    /** Diagnostics for the last call (shown in eval logs, never document text). */
    var lastZimArticles: List<String> = emptyList()
        private set

    /** Diagnostics: chosen passages with scores and per-term hits (no document text). */
    var lastChoice: String = ""
        private set

    /** True when the last [retrieve] call treated the question as a follow-up to its context. */
    var lastContinuedTopic: Boolean = false
        private set

    /**
     * [scope]: who is asking. MESH (a question that arrived by radio) may only use packs the
     * owner switched on for the mesh — plus the offline reference, which is public content.
     *
     * [context]: the question that opened the current topic, if any. A new question that
     * names an article of its own ("?how do I treat a burn") starts a new topic and the
     * context is ignored. One that doesn't ("?what if I have no seal") continues the topic:
     * the earlier subject picks the article, the new words pick the passage.
     */
    suspend fun retrieve(
        question: String,
        scope: com.bastion.terminal.data.Scope = com.bastion.terminal.data.Scope.ASK,
        context: String? = null,
        /** 0 = the passages to answer from. Above 0 = a wider pool of that many candidates, for a second ranking step. */
        wide: Int = 0,
    ): List<Passage> {
        lastContinuedTopic = false
        val ownTerms = Grounding.keyTerms(question)
        if (ownTerms.isEmpty() && context == null) return emptyList()
        val zimOpen = zim.currentInfos().isNotEmpty()
        fun subjectOf(q: String): Pair<List<String>, List<String>> {
            val all = if (Grounding.aspect(q) != null) Grounding.subjectTerms(q) else Grounding.keyTerms(q)
            // A model year ("2009 jeep wrangler") is rarely in the article that explains the
            // repair, and ranking on it pulled in an unrelated 2009 car (seen on emulator).
            val rank = all.filterNot { YEAR.matches(it) }.ifEmpty { all }
            // Only phrases that are most of the subject: "water" alone is not what a
            // bleach-per-gallon question is about.
            val phrases = Grounding.subjectPhrases(q).filter { p ->
                rank.isNotEmpty() && 2 * Grounding.keyTerms(p).count { it in rank } >= rank.size
            }
            return rank to phrases
        }
        val (ownRank, ownPhrases) = subjectOf(question)
        // Sources of truth (docs/library-plan.md): forums and unrated references are never searched.
        val useZim = { file: String -> SourceTier.forZim(file).usedForAnswers }
        // Exact-title articles first ("burn" → Burn), then the full-text top articles.
        var titleHits = if (zimOpen) zim.titleLookup(ownPhrases, use = useZim) else emptyList()
        var rankTerms = ownRank
        var aspect = Grounding.aspect(question)
        var libQuestion = question
        if (context != null && titleHits.isEmpty()) {
            // Follow-up: no subject of its own, so the open topic supplies it.
            val (ctxRank, ctxPhrases) = subjectOf(context)
            titleHits = if (zimOpen) zim.titleLookup(ctxPhrases, use = useZim) else emptyList()
            rankTerms = (ownRank + ctxRank).distinct().take(10)
            aspect = aspect ?: Grounding.aspect(context)
            libQuestion = "$question $context"
            lastContinuedTopic = true
        }
        val terms = if (lastContinuedTopic) rankTerms else ownTerms
        if (rankTerms.isEmpty()) return emptyList()
        val libTier = libraryTiers()
        val lib = store.retrieve(libQuestion, scope, wide)
            .filter { (libTier[it.docId] ?: SourceTier.OWN).usedForAnswers }
        val textHits = if (zimOpen) zim.search(rankTerms, if (wide > 0) MAX_ARTICLES * 2 else MAX_ARTICLES, use = useZim) else emptyList()
        // Same article in two ZIMs (WikiMed is a subset of full Wikipedia): keep one, from the
        // larger file (opened first), so duplicates don't crowd out other sources.
        val hits = (titleHits + textHits).distinctBy { it.title.lowercase() }
        val aboutPaths = titleHits.map { it.zim + "|" + it.path }.toSet()
        lastZimArticles = titleHits.map { "${it.title} (title)" } + textHits.map { "${it.title} (${it.score}%)" }
        if (hits.isEmpty()) {
            val keep = SourcePolicy.preferTruth(lib.map { (libTier[it.docId] ?: SourceTier.OWN) to "${it.label}\n${it.plainText}" }, rankTerms)
            return lib.filterIndexed { i, _ -> i in keep }.take(if (wide > 0) wide else lib.size)
                .mapIndexed { n, p -> p.copy(index = n + 1) }
        }

        data class Cand(
            val title: String, val heading: String?, val text: String, val docId: Long, val zimPath: String?,
            val zimFile: String? = null,
            val opensSection: Boolean = false,
            val tier: SourceTier = SourceTier.OWN,
        )
        val cands = ArrayList<Cand>()
        lib.forEach { cands += Cand(it.title, it.heading, it.plainText, it.docId, null, tier = libTier[it.docId] ?: SourceTier.OWN) }
        hits.forEachIndexed { i, h ->
            val article = zim.article(h.zim, h.path) ?: return@forEachIndexed
            val zimTitle = zim.infoFor(h.zim)?.title ?: "Offline reference"
            // Q&A answers start with their vote count on a line of its own ("48", blank line, text):
            // the model read "48" as "boil for 48 minutes" (measured 2026-11-24). Q&A ZIMs only:
            // elsewhere a bare number line can be a list step.
            val text = if (h.zim.contains("stackexchange", ignoreCase = true)) Grounding.stripVoteCounts(article.text) else article.text
            var prevHeading: String? = "0000"
            for (ch in Chunker.chunk(text)) {
                val body = text.substring(ch.start, ch.end).trim()
                if (body.length < MIN_ZIM_CHARS || Grounding.isPageFurniture(body)) continue
                val opens = ch.heading != prevHeading
                prevHeading = ch.heading
                // Negative ids keep ZIM articles distinct from library doc ids for the per-doc cap.
                cands += Cand("$zimTitle: ${article.title}", ch.heading, body, -(i + 1L), article.path, h.zim, opens, SourceTier.forZim(h.zim))
            }
        }
        // Score each passage with its title and heading ("Snakebite — Treatment"): a section
        // rarely repeats the subject its article title already names (seen on emulator:
        // without this, "Epidemiology" sections outranked "Snakebite › Treatment").
        val scored = Bm25.scoreInMemory(
            cands.mapIndexed { i, c -> Bm25.Item(i.toLong(), c.docId, "${c.title}\n${c.heading.orEmpty()}\n${c.text}") },
            rankTerms,
        )
        // Title prior: the article *about* the subject should win ("Hypothermia" over
        // "Shapiro syndrome" for hypothermia symptoms — seen on emulator). Up to +50%,
        // proportional to the share of key terms found in the passage's title; +100%
        // for an article found by exact title ("Burn" for "first aid for a burn" —
        // otherwise "First aid kit" wins on two of three words; seen on emulator).
        // Section prior: see [Grounding.aspectWeight] (Treatment / First aid for "how to treat",
        // Signs and symptoms for symptoms …). Section openers usually summarise: +10%.
        // When the question names an article outright, that article is the subject even if
        // its sections never repeat the asker's words: "sucking chest wound" resolves to
        // "Pneumothorax", whose Treatment section mentions neither "sucking" nor "wound" and
        // so scored zero (found 2026-11-22). Its passages get a floor, so the section the
        // question is after (aspect weight below) can win on what it is, not on word overlap.
        val topScore = scored.maxOfOrNull { it.score } ?: 0.0
        val boosted = scored.map { scRaw ->
            val about = cands[scRaw.id.toInt()].let { it.zimPath != null && "${it.zimFile}|${it.zimPath}" in aboutPaths }
            val sc = if (about) scRaw.copy(score = maxOf(scRaw.score, topScore * ABOUT_FLOOR)) else scRaw
            val c = cands[sc.id.toInt()]
            val articleTitle = if (c.zimPath != null) c.title.substringAfter(": ") else c.title
            val titleWords = Regex("[\\p{L}\\p{N}]+").findAll(articleTitle.lowercase()).map { it.value }.toList()
            val inTitle = rankTerms.count { t -> titleWords.any { it.startsWith(t) } }
            val titlePrior = if (c.zimPath != null && "${c.zimFile}|${c.zimPath}" in aboutPaths) 1.0 + TITLE_BOOST + ABOUT_BOOST
            else 1.0 + TITLE_BOOST * inTitle / rankTerms.size
            // Q&A references: the answers hold the how-to, the question only restates the problem.
            val answers = if (c.heading?.substringAfterLast(" › ")?.let { ANSWERS.matches(it) } == true) ANSWER_BOOST else 1.0
            val section = Grounding.aspectWeight(aspect, c.heading, articleTitle) * (if (c.opensSection) 1.1 else 1.0) * answers
            sc.copy(score = sc.score * titlePrior * section)
        }
        // Official before reference, and each text once (docs/library-plan.md).
        val keep = SourcePolicy.preferTruth(boosted.map { cands[it.id.toInt()].let { c -> c.tier to "${c.title}\n${c.heading.orEmpty()}\n${c.text}" } }, rankTerms, boosted.map { it.score })
        val ranked = boosted.filterIndexed { i, _ -> i in keep }
        val chosen = if (wide > 0) Bm25.select(ranked,{ cands[it.toInt()].text.length }, k = wide, perDoc = wide, charBudget = wide * 2000, relCutoff = 0.0)
        else Bm25.select(ranked, { cands[it.toInt()].text.length })
        lastChoice = chosen.joinToString(" ; ") { sc ->
            val c = cands[sc.id.toInt()]
            "${c.title.takeLast(28)}|${c.heading?.take(20)} s=${"%.2f".format(sc.score)} tf=${sc.termTf.toList()}"
        } + " | terms=$rankTerms df=${scored.firstOrNull()?.termDf?.toList()} n=${scored.size}"
        return chosen.mapIndexed { n, sc ->
            val c = cands[sc.id.toInt()]
            Passage(
                index = n + 1,
                docId = c.docId,
                title = c.title,
                markedText = Grounding.markTerms(c.text, terms),
                heading = c.heading,
                zimPath = c.zimPath,
                zimFile = c.zimFile,
            )
        }
    }

    /** Library document id → tier, from each document's provenance (bundle PDFs inherit their ZIM's tier). */
    private suspend fun libraryTiers(): Map<Long, SourceTier> =
        runCatching { store.listDocs().associate { it.id to SourceTier.forProvenance(it.provenance) } }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_ARTICLES = 5
        /** Skip stub fragments (lone captions, one-word lines). */
        const val MIN_ZIM_CHARS = 80
        const val TITLE_BOOST = 0.5
        /** Extra for the article found by exact title over ones that merely contain the word ("Burn" vs "Pediatric burn"). */
        const val ABOUT_BOOST = 0.5
        /** Share of the best lexical score every passage of an exact-title article gets at least. */
        const val ABOUT_FLOOR = 0.6
        val YEAR = Regex("(19|20)\\d\\d")
        /** Stack Exchange answer sections are headed "5 Answers5". */
        val ANSWERS = Regex("\\d+ Answers?\\d*")
        const val ANSWER_BOOST = 1.3
    }
}
