package com.bastion.terminal.mesh

import com.bastion.terminal.ai.Card
import com.bastion.terminal.ai.CardStore
import com.bastion.terminal.ai.GenerationStats
import com.bastion.terminal.ai.LocalModel
import com.bastion.terminal.mesh.MeshAutoResponder.CardMatch
import com.bastion.terminal.mesh.MeshAutoResponder.Command
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAutoResponderTest {
    private val question = "how do I sort the practice glyphs"
    private val first = "Place the pretend triangle in slot [2]."
    private val warning = "Keep the imaginary circles out of that slot."
    private val card = Card(
        id = "fake-glyph-sort", kind = "protocol", title = "Practice glyphs",
        asks = listOf(question), answer = listOf(first, "Count the make-believe squares."),
        warnings = listOf(warning), quotes = emptyList(), radio = listOf(first, warning),
    )
    private val unavailable =
        "BASTION: Not available by radio: Practice glyphs. Too long to send safely. Get to someone with BASTION or medical help."

    @Test
    fun sendsOnlyTheResolvedRadioLinesInOrderWithNoModelOrLibrary() = runBlocking {
        val cards = CardStore.parse(
            """
            {"cards":[{"id":"fake-glyph-sort","kind":"protocol","title":"Practice glyphs",
            "asks":["$question"],"answer":["$first","Count the make-believe squares."],
            "warnings":["$warning"],"quotes":[],"radio":["$first","$warning"]}]}
            """.trimIndent(),
        )
        val parts = MeshAutoResponder.answerFor("  HOW do I sort the   practice glyphs? ", cards)
        assertEquals(listOf("BASTION: Practice glyphs 1. $first 2. $warning"), parts)
        assertTrue(parts.all { MeshBridge.byteLength(it) <= 200 })
    }

    @Test
    fun matchedCardWithoutAValidRadioVersionGetsTheExactNotice() = runBlocking {
        assertEquals(listOf(unavailable), MeshAutoResponder.answerFor(question, listOf(card.copy(radio = emptyList()))))
        val extra = card.copy(radio = listOf(first, "An unapproved imaginary step."))
        assertEquals(listOf(unavailable), MeshAutoResponder.answerFor(question, listOf(extra)))
        val legacy = CardStore.parse(
            """{"cards":[{"id":"old-fake","kind":"protocol","title":"Practice glyphs",
                "asks":["$question"],"answer":["$first"],"quotes":[]}]}""",
        )
        assertEquals(listOf(unavailable), MeshAutoResponder.answerFor(question, legacy))
    }

    @Test
    fun unmatchedQuestionsGetTheExactNoticeWithNoModelOrLibrary() = runBlocking {
        val notice = listOf("BASTION: Not covered by radio.")
        assertEquals(notice, MeshAutoResponder.answerFor("should I discard the practice glyphs", listOf(card)))
        assertEquals(notice, MeshAutoResponder.answerFor(question, emptyList()))
    }

    @Test
    fun exactTitleAndUniqueSubjectMatchDoNotObtainAModel() = runBlocking {
        var loads = 0
        val model: suspend () -> LocalModel? = { loads++; null }
        val unrelated = card.copy(id = "fake-moons", title = "Paper moons", asks = listOf("count the pretend moons"))
        val cards = listOf(card, unrelated)
        assertEquals(CardMatch.Found(card), MeshAutoResponder.matchCard(" PRACTICE   GLYPHS? ", cards, model))
        assertEquals(CardMatch.Found(card), MeshAutoResponder.matchCard("sort practice glyphs", cards, model))
        assertEquals(0, loads)
    }

    @Test
    fun instructionCrossReferenceCannotTurnASoleCandidateIntoARadioMatch() = runBlocking {
        val folding = card.copy(
            title = "Fold practice glyphs",
            asks = listOf("how to fold practice glyphs"),
            answer = listOf("Sort practice glyphs with the other card."),
        )
        assertEquals(CardMatch.NotCovered, MeshAutoResponder.matchCard("sort practice glyphs", listOf(folding)))
    }

    @Test
    fun aSoleCandidateMustCoverRequestedIntentAsWellAsTopic() = runBlocking {
        assertEquals(CardMatch.NotCovered, MeshAutoResponder.matchCard("what causes practice glyphs", listOf(card)))
    }

    @Test
    fun fallbackCanConfirmASpellingSlipAndOnlySendsApprovedLines() = runBlocking {
        val glimmer = card.copy(title = "Glimmer sigils", asks = listOf("sort glimmer sigils"))
        val picker = Picker("""{"choice":1}""")
        val parts = MeshAutoResponder.answerFor("sort glimer sigils", listOf(glimmer)) { picker }
        assertTrue(picker.prompt.contains(glimmer.title))
        assertEquals(listOf("BASTION: Glimmer sigils 1. $first 2. $warning"), parts)
    }

    @Test
    fun fallbackMenuNeedsStrongRankingAndTheModelCannotFillInAnUnsupportedIntent() = runBlocking {
        val glimmer = card.copy(title = "Glimmer sigils", asks = listOf("sort glimmer sigils"))
        val otherCards = (1..40).map { n ->
            card.copy(id = "paper-$n", title = "Fold paper moon $n", asks = listOf("fold paper moons"))
        }
        val cards = listOf(glimmer) + otherCards
        val typo = "sort glimer sigils"
        assertEquals(CardMatch.Menu(listOf(glimmer)), MeshAutoResponder.matchCard(typo, cards))
        assertEquals(CardMatch.NotCovered, MeshAutoResponder.matchCard(typo, listOf(glimmer)))
        assertEquals(CardMatch.Menu(listOf(glimmer)), MeshAutoResponder.matchCard(typo, cards) { Picker("""{"choice":0}""") })
        assertEquals(CardMatch.Menu(listOf(glimmer)), MeshAutoResponder.matchCard(typo, cards) { Picker("""Extra text {"choice":1}""") })
        val differentIntent = MeshAutoResponder.matchCard("sort glimer sigils for barter", cards) { Picker("""{"choice":1}""") }
        assertFalse(differentIntent is CardMatch.Found)
    }

    @Test
    fun fallbackUsesOnlyTheTopFiveAndRequiresUniqueEvidence() = runBlocking {
        val cards = (1..8).map { n ->
            card.copy(id = "sigil-$n", title = "Glimmer sigils $n", asks = listOf("sort glimmer sigils"))
        }
        val validNumber = Picker("""{"choice":1}""")
        val outsideShortlist = Picker("""{"choice":6}""")
        val match = MeshAutoResponder.matchCard("sort glimer sigils", cards) { validNumber }
        val invalid = MeshAutoResponder.matchCard("sort glimer sigils", cards) { outsideShortlist }
        assertTrue(validNumber.prompt.contains("5. Glimmer sigils 5"))
        assertFalse(validNumber.prompt.contains("6. Glimmer sigils 6"))
        assertFalse(match is CardMatch.Found)
        assertEquals(match, invalid)
    }

    @Test
    fun menuTitlesTakePrecedenceOverAnAliasAndNavigationVerbs() = runBlocking {
        val title = "Find the pretend glyphs"
        val intended = card.copy(title = title)
        val other = card.copy(id = "other-fake", asks = listOf(title))
        val cards = listOf(intended, other)
        assertEquals(CardMatch.Found(intended), MeshAutoResponder.matchCard(title, cards))
        assertNull(MeshAutoResponder.navigationTarget(title, cards))
        assertEquals("base", MeshAutoResponder.navigationTarget("where is base", cards))
    }

    @Test
    fun modelCanPickAShortlistedCardButItsTextNeverReachesTheReply() = runBlocking {
        val other = card.copy(id = "other-fake", title = "Classroom glyphs")
        val unrelated = card.copy(id = "fake-moons", title = "Paper moons", asks = listOf("count the pretend moons"))
        val cards = listOf(card, other, unrelated)
        val picker = Picker("""{"choice":2}""")
        val parts = MeshAutoResponder.answerFor("sort practice glyphs", cards) { picker }
        assertEquals(listOf("BASTION: Classroom glyphs 1. $first 2. $warning"), parts)
        assertTrue(picker.prompt.contains("Practice glyphs") && picker.prompt.contains("Classroom glyphs"))
        assertFalse(picker.prompt.contains("Paper moons"))
    }

    @Test
    fun failedAndUnavailableModelPicksOfferTheSameMenu() = runBlocking {
        val other = card.copy(id = "other-fake", title = "Classroom glyphs")
        val cards = listOf(card, other)
        val ask = "sort practice glyphs"
        val expected = listOf("BASTION: Not sure which. Send one: ?Practice glyphs / ?Classroom glyphs")
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards))
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards) { error("Model unavailable") })
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards) { Picker("""{"choice":0}""") })
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards) { Picker("""{"choice":9}""") })
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards) { Picker("""{"choice":1.5}""") })
        assertEquals(expected, MeshAutoResponder.answerFor(ask, cards) { Picker(null) })
        assertEquals(CardMatch.Found(other), MeshAutoResponder.matchCard("Classroom glyphs", cards))
    }

    @Test
    fun menuDropsWholeTitlesToFitOnePacket() = runBlocking {
        val cards = (1..4).map { i ->
            card.copy(id = "fake-$i", title = "$i" + "é".repeat(34))
        }
        val parts = MeshAutoResponder.answerFor(question, cards)
        val expected = "BASTION: Not sure which. Send one: " +
            cards.take(2).joinToString(" / ") { "?${it.title}" }
        assertEquals(listOf(expected), parts)
        assertTrue(MeshBridge.byteLength(parts.single()) <= 200)
    }

    @Test
    fun multipartUnavailableNoticeIsNumbered() = runBlocking {
        val title = "X".repeat(130)
        val parts = MeshAutoResponder.answerFor(question, listOf(card.copy(title = title, radio = emptyList())))
        assertEquals(2, parts.size)
        parts.forEachIndexed { index, text -> assertTrue(text.startsWith("BASTION ${index + 1}/2: ")) }
        assertTrue(parts.all { MeshBridge.byteLength(it) <= 200 })
        assertEquals(
            "Not available by radio: $title. Too long to send safely. Get to someone with BASTION or medical help.",
            parts.joinToString(" ") { it.substringAfter(": ") },
        )
    }

    @Test
    fun byteBudgetUsesTheRealPrefixAndAppliesToTheEntireReply() = runBlocking {
        val line = "é".repeat(91) + "x"
        val short = card.copy(title = "Demo", answer = listOf(line), radio = listOf(line))
        val onePart = MeshAutoResponder.answerFor(question, listOf(short))
        assertEquals(listOf("BASTION: Demo 1. $line"), onePart)
        assertEquals(200, MeshBridge.byteLength(onePart.single()))

        val ordered = (1..4).map { n -> "$n" + "é".repeat(88) + "." }
        val four = card.copy(title = "Demo", answer = ordered, radio = ordered)
        val parts = MeshAutoResponder.answerFor(question, listOf(four))
        assertEquals(4, parts.size)
        assertTrue(parts.all { MeshBridge.byteLength(it) <= 200 })
        assertEquals(
            "Demo " + ordered.mapIndexed { i, text -> "${i + 1}. $text" }.joinToString(" "),
            parts.joinToString(" ") { it.substringAfter(": ") },
        )
        val tooMany = card.copy(answer = ordered, radio = ordered + ordered.first())
        assertEquals(listOf(unavailable), MeshAutoResponder.answerFor(question, listOf(tooMany)))
        val tooLong = "🚀".repeat(60)
        val unbreakable = card.copy(answer = listOf(tooLong), radio = listOf(tooLong))
        assertEquals(listOf(unavailable), MeshAutoResponder.answerFor(question, listOf(unbreakable)))
    }

    private class Picker(private val reply: String?) : LocalModel {
        override val label = "Fake picker"
        var prompt = ""
            private set
        override suspend fun load() = Unit
        override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int): Flow<String> =
            error("The radio picker must use constrained choice")
        override suspend fun generateJson(systemInstruction: String, userPrompt: String, jsonSchema: String, maxOutputTokens: Int): String {
            prompt = userPrompt
            return reply ?: error("Fake runtime failure")
        }
        override fun lastStats(): GenerationStats? = null
        override fun cancel() = Unit
        override fun close() = Unit
    }

    /** Standing rule: only a leading symbol is a command; everything else is chat. */
    @Test
    fun commandIn_onlyLeadingSymbolsAreCommands() {
        assertEquals(Command.Ask("how do I treat a burn"), MeshAutoResponder.commandIn("?how do I treat a burn"))
        assertEquals(Command.Ask("how do I treat a burn"), MeshAutoResponder.commandIn("  ? how do I treat a burn? "))
        assertEquals(Command.Locate("base"), MeshAutoResponder.commandIn("@base"))
        assertEquals(Command.Locate("Sim B"), MeshAutoResponder.commandIn("@ Sim B"))
        // Ordinary chat is never a command — not even a well-formed question, and not a word trigger.
        assertNull(MeshAutoResponder.commandIn("How do I deal with a sucking chest wound"))
        assertNull(MeshAutoResponder.commandIn("Where are you?"))
        assertNull(MeshAutoResponder.commandIn("bastion, symptoms of hypothermia"))
        assertNull(MeshAutoResponder.commandIn("Need water at checkpoint 2"))
        // Empty triggers and BASTION's own replies are ignored.
        assertNull(MeshAutoResponder.commandIn("?"))
        assertNull(MeshAutoResponder.commandIn("@"))
        assertNull(MeshAutoResponder.commandIn("AI: cool the burn with running water"))
        assertNull(MeshAutoResponder.commandIn("AI 1/2: 1. Cover the wound."))
        assertNull(MeshAutoResponder.commandIn("BASTION: Practice glyphs"))
        assertNull(MeshAutoResponder.commandIn("BASTION 1/2: Practice glyphs"))
        assertNull(MeshAutoResponder.commandIn("NAV: BASTION: 3.1 km at 183 deg S"))
    }

    @Test
    fun isPublicChannel_defaultPresetNamesArePublic() {
        assertTrue(MeshAutoResponder.isPublicChannel("LongFast"))
        assertTrue(MeshAutoResponder.isPublicChannel(" mediumslow "))
        assertTrue(MeshAutoResponder.isPublicChannel(""))
        assertFalse(MeshAutoResponder.isPublicChannel("Bastion"))
        assertFalse(MeshAutoResponder.isPublicChannel("Team Kestrel"))
    }
}
