package com.bastion.terminal.ai

/**
 * How far a source can be trusted as the basis of an AI answer (owner decision 2026-11-24,
 * docs/library-plan.md). Lower ordinal = more trusted.
 *
 * - [OWN]: documents the owner imported, wrote or put in the kit's docs folder.
 * - [OFFICIAL]: government, military, public-health and standards bodies.
 * - [REFERENCE]: reputable but volunteer-edited or mixed collections; used only when no
 *   OWN/OFFICIAL passage covers the question.
 * - [FORUM]: Q&A and discussion sites; never used for AI answers.
 * - [UNRATED]: a reference BASTION cannot classify; never used until it is added here.
 */
enum class SourceTier(val label: String, val usedForAnswers: Boolean) {
    OWN("your library", true),
    OFFICIAL("official", true),
    REFERENCE("reference", true),
    FORUM("forum — not used for AI answers", false),
    UNRATED("unrated — not used for AI answers", false);

    val isTruth: Boolean get() = this == OWN || this == OFFICIAL

    companion object {
        // Matched against the ZIM file name ("wwwnc.cdc.gov_en_all_2024-11.zim"). First match wins.
        private val ZIM_RULES: List<Pair<Regex, SourceTier>> = listOf(
            Regex("stackexchange|stackoverflow|reddit|forum", RegexOption.IGNORE_CASE) to FORUM,
            // Government / military / public health.
            Regex("(^|[._-])(cdc\\.gov|medlineplus\\.gov|nhs\\.uk|irp\\.fas\\.org|fema\\.gov|epa\\.gov|who\\.int|usda\\.gov|nih\\.gov|noaa\\.gov|ready\\.gov)", RegexOption.IGNORE_CASE) to OFFICIAL,
            // Reputable but volunteer-edited, or mixed document collections.
            Regex("^(wikipedia|wikimed|ifixit|appropedia|energypedia|zimgit-post-disaster)", RegexOption.IGNORE_CASE) to REFERENCE,
        )

        /** Tier of a ZIM reference, from its file name (path or bare name). */
        fun forZim(file: String): SourceTier {
            val name = file.substringAfterLast('/').substringAfterLast('\\')
            return ZIM_RULES.firstOrNull { it.first.containsMatchIn(name) }?.second ?: UNRATED
        }

        private val FROM_ZIM = Regex("\\(([^()]+\\.zim)\\)")

        /**
         * Tier of a library document, from its provenance. PDFs taken out of a ZIM bundle
         * ("From “Post-disaster…” (zimgit-post-disaster_en_2024-05.zim)") get that ZIM's tier;
         * everything else in the library was put there by the owner.
         */
        fun forProvenance(provenance: String): SourceTier =
            FROM_ZIM.find(provenance)?.let { forZim(it.groupValues[1]) } ?: OWN
    }
}

/** Which candidate passages may be used, given their tiers (pure; unit tested). */
object SourcePolicy {

    /** Two passages sharing this share of 5-word runs are the same text. */
    const val SAME_TEXT = 0.5
    /** A trusted passage displaces reference ones only if it scores at least this share of the best. */
    const val STRONG = 0.5
    private const val SHINGLE = 5
    /** Duplicates are looked for among this many best candidates (cost is quadratic). */
    private const val DEDUP_TOP = 60

    /**
     * Indices of [items] (tier, label + text) to keep:
     *  1. if any OWN/OFFICIAL passage contains every subject term ([terms], stemmed prefixes),
     *     REFERENCE passages are dropped: the official source answers, the wiki is not needed;
     *  2. of passages with the same text, only the most trusted copy (then the best scored) stays.
     * [scores] order the duplicates; without them, list order is the rank.
     */
    fun preferTruth(items: List<Pair<SourceTier, String>>, terms: List<String>, scores: List<Double>? = null): Set<Int> {
        val lower = items.map { it.second.lowercase() }
        fun covers(i: Int) = terms.isNotEmpty() && terms.all { Regex("\\b" + Regex.escape(it)).containsMatchIn(lower[i]) }
        // "Covers" means a strong match, not a mention: a sample note that names hypothermia once
        // displaced WikiMed's Hypothermia article (ZimRetrievalEvalTest, 2026-11-24).
        val best = scores?.maxOrNull() ?: 0.0
        fun strong(i: Int) = scores == null || scores[i] >= STRONG * best
        val truthCovers = items.indices.any { items[it].first.isTruth && covers(it) && strong(it) }
        val allowed = items.indices.filter { items[it].first.usedForAnswers && !(truthCovers && items[it].first == SourceTier.REFERENCE) }
        val order = allowed.sortedWith(compareBy<Int> { items[it].first.ordinal }.thenByDescending { scores?.get(it) ?: -it.toDouble() })
        val kept = LinkedHashMap<Int, Set<String>>()
        val keep = HashSet<Int>()
        for ((rank, i) in order.withIndex()) {
            if (rank >= DEDUP_TOP) { keep += i; continue }
            val sh = shingles(lower[i])
            if (sh.isNotEmpty() && kept.values.any { jaccard(it, sh) >= SAME_TEXT }) continue
            kept[i] = sh; keep += i
        }
        return keep
    }

    fun shingles(text: String): Set<String> {
        val w = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (w.size < SHINGLE) emptySet() else (0..w.size - SHINGLE).mapTo(HashSet()) { w.subList(it, it + SHINGLE).joinToString(" ") }
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter).coerceAtLeast(1)
    }
}
