package com.bastion.terminal.mesh

import com.bastion.terminal.ai.Card
import com.bastion.terminal.ai.CardRouter
import com.bastion.terminal.ai.Grounding
import kotlin.math.abs

internal object RadioCardFallback {
    private const val MAX_CHOICES = 5
    private const val MIN_MENU_SCORE = 5.0
    private const val MENU_SCORE_FRACTION = 0.35
    private val EPI_PEN = Regex("\\bepi[ -]+pen\\b")
    private val ABBREVIATION = Regex("\\b(tq|ki|ors)\\b")
    private val EXPANDED = mapOf(
        "tq" to "tourniquet",
        "ki" to "potassium iodide",
        "ors" to "oral rehydration solution",
    )
    private val CHAT_WORDS = setOf("buddy", "him", "her", "now", "whats", "plz", "over", "again")
        .map(Grounding::stem).toSet()

    data class Shortlist(val cards: List<Card>, val menuCards: List<Card>)

    fun shortlist(question: String, cards: List<Card>): Shortlist {
        val terms = Grounding.keyTerms(expand(question))
        val ranked = CardRouter.rankedCandidates(terms, cards, MAX_CHOICES, useListedQuestions = true)
        val bestScore = ranked.firstOrNull()?.score ?: 0.0
        val menu = if (bestScore >= MIN_MENU_SCORE) {
            ranked.takeWhile { it.score >= bestScore * MENU_SCORE_FRACTION }.map { it.card }
        } else emptyList()
        val choices = ranked.ifEmpty { CardRouter.rankedCandidates(terms, cards, MAX_CHOICES) }
        return Shortlist(choices.map { it.card }, menu)
    }

    /**
     * The chosen card must be the sole card whose title and listed questions account for
     * every search term. At least two terms must describe the subject, including one that
     * appears in no more than a fifth of cards. Answers and quotes supply no evidence here.
     */
    fun supportsChoice(question: String, picked: Card, cards: List<Card>): Boolean {
        val source = EPI_PEN.replace(question.lowercase(), "epipen")
        if (contentTerms(source).count { !Grounding.isAspectTerm(it) } < 2) return false
        val terms = contentTerms(expand(source))
        val subject = terms.filterNot(Grounding::isAspectTerm)

        val listings = cards.map { card ->
            val text = (listOf(card.title) + card.asks).joinToString(" ")
            Grounding.keyTerms(expand(text), max = Int.MAX_VALUE).toSet()
        }
        val vocabulary = listings.flatten().toSet()
        val matches = terms.associateWith { term ->
            listings.indices.filter { index ->
                listings[index].any { word -> matchesSpelling(term, word, term in vocabulary) }
            }.toSet()
        }
        val index = cards.indexOf(picked)
        val supported = cards.indices.singleOrNull { i -> matches.values.all { i in it } }
        if (supported != index) return false
        val rareLimit = (cards.size / 5).coerceAtLeast(1)
        return subject.any { matches.getValue(it).size <= rareLimit }
    }

    private fun expand(text: String): String =
        ABBREVIATION.replace(EPI_PEN.replace(text.lowercase(), "epipen")) { EXPANDED.getValue(it.value) }

    private fun contentTerms(text: String): List<String> =
        Grounding.keyTerms(text, max = Int.MAX_VALUE).filterNot { it in CHAT_WORDS }

    private fun matchesSpelling(term: String, word: String, known: Boolean): Boolean {
        if (term == word) return true
        if (known || minOf(term.length, word.length) < 5) return false
        val difference = abs(term.length - word.length)
        if (difference <= 2 && (word.startsWith(term) || term.startsWith(word))) return true
        if (difference > 1) return false

        val first = term.indices.firstOrNull { it >= word.length || term[it] != word[it] } ?: term.length
        return when {
            difference == 0 -> term.substring(first + 1) == word.substring(first + 1) ||
                (first + 1 < term.length && term[first] == word[first + 1] &&
                    term[first + 1] == word[first] && term.substring(first + 2) == word.substring(first + 2))
            term.length < word.length -> term.substring(first) == word.substring(first + 1)
            else -> term.substring(first + 1) == word.substring(first)
        }
    }
}
