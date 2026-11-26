package com.bastion.terminal.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.Bm25
import com.bastion.terminal.data.Chunker
import com.bastion.terminal.data.DocTitle
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.data.TextUtil
import com.bastion.terminal.kiwix.HtmlText
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.link.TakContact
import com.bastion.terminal.map.Pins
import com.bastion.terminal.mesh.MeshAutoResponder
import com.bastion.terminal.mesh.MeshBridge
import com.bastion.terminal.mesh.RadioCardFallback
import com.bastion.terminal.nav.Nav
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's regex engine without opening a model, library document or radio. */
@RunWith(AndroidJUnit4::class)
class RegexInitializationSmokeTest {
    @Test
    fun askAndRadioRegexHoldersInitialize() {
        val holders = listOf(
            CardRouter::class.java,
            RadioCardFallback::class.java,
            MeshAutoResponder::class.java,
            Grounding::class.java,
            Grounding.Aspect::class.java,
            CheckedAnswer::class.java,
            CardStore::class.java,
            SourceTier::class.java,
            Retriever::class.java,
            LibraryStore::class.java,
            Chunker::class.java,
            Bm25::class.java,
            DocTitle::class.java,
            MeshBridge::class.java,
            Pins::class.java,
        )
        // Class literals alone do not force the static initializers to compile the Regex fields.
        holders.forEach { Class.forName(it.name, true, it.classLoader) }

        // ZIM search patterns are instance fields. Construction leaves all archives closed.
        ZimLibrary.get(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun askAndRadioParseChoiceOnAndroid() = runBlocking<Unit> {
        val card = CardStore.parse(
            """{"cards":[{"id":"sample","kind":"answer","title":"Sample Widget","asks":["How does a sample widget work?"],"answer":["Sample widget contains 2 parts."],"quotes":[]}]}""",
        ).single()
        val model = fixedReply("""{ "choice": 1 }""")
        // Exercise both the Ask parser and the radio path's strict object parser.
        assertEquals(card, CardRouter.route(model, "sample widget", listOf(card)))
        assertEquals(card, CardRouter.pick(model, "sample widget", listOf(card), requireChoiceObject = true))
        assertEquals(
            MeshAutoResponder.CardMatch.Found(card),
            MeshAutoResponder.matchCard("  sample  widget  ", listOf(card)),
        )
    }

    @Test
    fun groundingAndSourceParsingCompilePatternsUsedDuringCalls() = runBlocking<Unit> {
        val note = "Sample widget contains 2 parts."
        val passage = Passage(index = 1, docId = 1L, title = "Sample Widget", markedText = note)
        val plan = QueryPlan.parse(
            """{"standalone_question":"sample widget","keyword_query":"widget","relation":"contains","not_about":"gadget"}""",
        )
        assertEquals("sample widget", plan?.standalone)

        // A neutral term reaches every branch pattern in Grounding.aspect.
        Grounding.aspect("widget")
        Grounding.subjectPhrases("sample widget")
        Grounding.termHits(note, listOf("widget"))
        Grounding.isTable("2 3 4\n5 6 7")
        assertEquals(setOf(1), Grounding.checkCitations("Sample widget contains 2 parts. [1]", 1).cited)
        assertTrue(CheckedAnswer.check("sample widget", "Sample widget contains 2 parts. [1]", listOf(passage)).isAnswer)
        assertTrue(CheckedAnswer.judge(fixedReply("""{"verdict":"responds"}"""), "sample widget", note))

        SourcePolicy.preferTruth(listOf(SourceTier.OWN to note), listOf("widget"))
        Chunker.chunk(note, target = 20, max = 20)
        DocTitle.pick(null, "Sample Widget\n$note", "sample.md")
        TextUtil.ftsQuery("sample widget")
        TextUtil.highlightRanges(note, "widget")
        TextUtil.stripHeadings("# Sample Widget\n$note")
        HtmlText.extract("<h1>Sample Widget</h1><p>$note</p>")
        HtmlText.redirectTarget("<p>Sample widget</p>")
    }

    @Test
    fun navigationAndShareCompilePatternsUsedDuringCalls() {
        val contact = TakContact(
            uid = "sample", callsign = "Alpha Team", type = "", lat = 0.0, lon = 0.0,
            battery = null, team = null, role = null, seenAt = 0L,
        )
        assertEquals("Alpha", Nav.targetOf("where is Alpha?"))
        assertEquals(contact, Nav.findContact("Alpha", listOf(contact)))
        assertNotNull(Pins.parse(Pins.shareText("Sample Pin", 12.345, -67.890)))
        assertEquals("Sample widget.", MeshBridge.fit("Sample widget [1].").first)
    }

    private fun fixedReply(reply: String) = object : LocalModel {
        override val label = "Fixed test reply"
        override suspend fun load() = Unit
        override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int): Flow<String> =
            flowOf(reply)
        override fun lastStats(): GenerationStats? = null
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
