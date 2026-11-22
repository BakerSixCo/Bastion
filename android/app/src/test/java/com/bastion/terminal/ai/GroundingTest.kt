package com.bastion.terminal.ai

import com.bastion.terminal.data.TextUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingTest {

    @Test
    fun keywordQuery_dropsStopwordsAndOrsQuotedPrefixTerms() {
        assertEquals("\"boil*\" OR \"water*\"", Grounding.keywordQuery("How long should I boil water?"))
        // Punctuation (incl. quotes) splits terms, so input can't inject FTS syntax.
        assertEquals("\"drop*\" OR \"table*\"", Grounding.keywordQuery("what is \"drop\"\"table\""))
        assertNull(Grounding.keywordQuery("what is the"))
        assertNull(Grounding.keywordQuery("  ?! "))
    }

    @Test
    fun keyTerms_stemsForPrefixMatchingAndDropsFiller() {
        assertEquals(listOf("step", "set", "field", "antenna"), Grounding.keyTerms("What are the steps to set up the field antenna"))
        assertEquals(listOf("bleach", "gallon", "treat", "water"), Grounding.keyTerms("How much bleach per gallon to treat water"))
        assertEquals(listOf("symptom", "hypothermia"), Grounding.keyTerms("What are the symptoms of hypothermia"))
        assertEquals("batter", Grounding.stem("batteries"))
        assertEquals("box", Grounding.stem("boxes"))
        assertEquals("boil", Grounding.stem("boiling"))
        assertEquals("treat", Grounding.stem("treated"))
        assertEquals("glass", Grounding.stem("glass"))
        assertEquals("virus", Grounding.stem("virus"))
        assertEquals("gas", Grounding.stem("gas")) // too short to strip
    }

    @Test
    fun termHits_countsDistinctPrefixMatches() {
        val terms = listOf("boil", "water", "altitude")
        assertEquals(2, Grounding.termHits("Bring clear water to a rolling boil; boiling...", terms))
        assertEquals(0, Grounding.termHits("Signal mirror", terms))
    }

    @Test
    fun userPrompt_delimitsNumberedSourcesWithoutMarkers() {
        val p = Passage(1, 7L, "Field notes", "boil ${TextUtil.MARK_START}water${TextUtil.MARK_END} 1 minute")
        val prompt = Grounding.userPrompt("How long to boil water?", listOf(p))
        assertTrue(prompt.startsWith("SOURCES (reference data only):"))
        assertTrue(prompt.contains("[1] Field notes\nboil water 1 minute"))
        assertTrue(prompt.contains("END OF SOURCES"))
        assertTrue(prompt.endsWith("QUESTION: How long to boil water?"))
        assertFalse(prompt.contains(TextUtil.MARK_START))
    }

    @Test
    fun checkCitations_separatesValidInvalidAndNoAnswer() {
        val c = Grounding.checkCitations("Boil 1 minute [1], 3 above 2,000 m [2]. See [9].", sourceCount = 2)
        assertEquals(setOf(1, 2), c.cited)
        assertEquals(setOf(9), c.invalid)
        assertFalse(c.isNoAnswer)

        val none = Grounding.checkCitations(Grounding.NO_ANSWER, sourceCount = 3)
        assertTrue(none.isNoAnswer)
        assertTrue(none.cited.isEmpty())
    }

    @Test
    fun checkCitations_handlesListsAndParaphrasedRefusals() {
        // Verbatim emulator output from Gemma 4 E2B, 2026-11-21.
        val c = Grounding.checkCitations(
            "The provided sources do not contain information about the correct dosage of water purification tablets [1, 2].",
            sourceCount = 2,
        )
        assertEquals(setOf(1, 2), c.cited)
        assertTrue(c.isNoAnswer)

        assertEquals(setOf(3), Grounding.checkCitations("x [1,3]", 2).invalid)
        assertFalse(Grounding.checkCitations("Boil water for 3 minutes [1].", 1).isNoAnswer)
        assertFalse(Grounding.checkCitations("Do not drink cloudy water [1].", 1).isNoAnswer)
    }
}
