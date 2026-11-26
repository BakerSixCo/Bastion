package com.bastion.terminal.mesh

import com.bastion.terminal.ai.Card
import com.bastion.terminal.ai.CardStore
import com.bastion.terminal.mesh.MeshAutoResponder.Command
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
        "Not available by radio: Practice glyphs. Too long to send safely. Get to someone with BASTION or medical help."

    @Test
    fun sendsOnlyTheResolvedRadioLinesInOrderWithNoModelOrLibrary() {
        val cards = CardStore.parse(
            """
            {"cards":[{"id":"fake-glyph-sort","kind":"protocol","title":"Practice glyphs",
            "asks":["$question"],"answer":["$first","Count the make-believe squares."],
            "warnings":["$warning"],"quotes":[],"radio":["$first","$warning"]}]}
            """.trimIndent(),
        )
        val parts = MeshAutoResponder.answerFor("  HOW do I sort the   practice glyphs? ", cards)
        assertEquals(listOf("AI: Practice glyphs 1. $first 2. $warning"), parts)
        assertTrue(parts.all { MeshBridge.byteLength(it) <= 200 })
    }

    @Test
    fun matchedCardWithoutAValidRadioVersionGetsTheExactNotice() {
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
    fun unmatchedAndAmbiguousQuestionsGetTheExactNoticeWithNoModelOrLibrary() {
        val notice = listOf("Not covered by radio.")
        assertEquals(notice, MeshAutoResponder.answerFor("should I discard the practice glyphs", listOf(card)))
        assertEquals(notice, MeshAutoResponder.answerFor(question, emptyList()))
        assertEquals(notice, MeshAutoResponder.answerFor(question, listOf(card, card.copy(id = "other-fake"))))
    }

    @Test
    fun byteBudgetUsesTheRealPrefixAndAppliesToTheEntireReply() {
        val line = "é".repeat(94)
        val short = card.copy(title = "Demo", answer = listOf(line), radio = listOf(line))
        val onePart = MeshAutoResponder.answerFor(question, listOf(short))
        assertEquals(listOf("AI: Demo 1. $line"), onePart)
        assertEquals(200, MeshBridge.byteLength(onePart.single()))

        val ordered = (1..4).map { n -> "$n" + "é".repeat(91) + "." }
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
