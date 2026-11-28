package com.bastion.terminal.ai

import com.bastion.terminal.ai.CheckedAnswer.Failed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases are shaped on real answers from the 156-question run (tools/model-eval, 2026-11-24). */
class CheckedAnswerTest {

    private val canning = Passage(1, 1, "USDA Complete Guide to Home Canning",
        "To ensure safe acidity in whole, crushed, or juiced tomatoes, add 2 tablespoons of bottled lemon juice per quart.")
    private val noaa = Passage(1, 2, "NOAA Weather Radio Stations",
        "NOAA Weather Radio for Custer County, Oklahoma: Clinton, OK on 162.525 MHz (WXK87).")
    private val etrex = Passage(1, 3, "The Great Outdoors Q&A: Is it possible to delete Geocaches from a Garmin etrex?",
        "To do a factory reset: press and hold the enter button, then press the power/light button.")
    private val inreach = Passage(2, 3, "The Great Outdoors Q&A: How best to hold a Garmin InReach for satellite messaging?",
        "Hold the inReach with the antenna pointing at the sky.")
    private val bleeding = Passage(1, 4, "Special Operations Forces Medical Handbook — Hemorrhage",
        "Apply direct pressure to the wound. If bleeding from an arm or leg is not controlled, apply a tourniquet above the wound.")

    @Test fun aSupportedCitedAnswerIsShownUnchanged() {
        val a = "Add 2 tablespoons of bottled lemon juice per quart of tomatoes [1]."
        val d = CheckedAnswer.check("how much lemon juice do I add to a quart of tomatoes when canning", a, listOf(canning))
        assertTrue(d.isAnswer)
        assertEquals(a, d.shown)
    }

    @Test fun refusalsAndEmptyRepliesShowTheFixedText() {
        for (a in listOf("", "  ", Grounding.NO_ANSWER, "The sources do not contain an answer about this.")) {
            val d = CheckedAnswer.check("how do I can tomatoes", a, listOf(canning))
            assertEquals(Failed.REFUSAL, d.failed)
            assertEquals(Grounding.NO_ANSWER, d.shown)
        }
        assertEquals(Failed.REFUSAL, CheckedAnswer.check("anything", "Some answer [1].", emptyList()).failed)
    }

    @Test fun anAnswerAboutADifferentSubjectIsBlocked() {
        val sheriff = CheckedAnswer.check("what radio channel does the Custer county sheriff use",
            "The NOAA Weather Radio for Custer County, Oklahoma is on 162.525 MHz [1].", listOf(noaa))
        assertEquals(Failed.SUBJECT, sheriff.failed)
        assertTrue(sheriff.missing.contains("sheriff"))
        assertEquals(Grounding.NO_ANSWER, sheriff.shown)

        // The quote came from the eTrex thread; the inReach thread was there but not cited.
        val garmin = CheckedAnswer.check("how do I reset a Garmin inReach",
            "Press and hold the enter button, then press the power/light button [1].", listOf(etrex, inreach))
        assertEquals(Failed.SUBJECT, garmin.failed)
        assertEquals(listOf("inreach"), garmin.missing)
    }

    @Test fun uncitedAnswersAreCheckedAgainstAllTheirSources() {
        val d = CheckedAnswer.check("what radio channel does the Custer county sheriff use", "It is on 162.525 MHz.", listOf(noaa))
        assertEquals(Failed.SUBJECT, d.failed)
    }

    @Test fun fillerAndSlangDoNotBlockAGoodAnswer() {
        val a = "- Apply direct pressure to the wound [1].\n- If it does not stop, apply a tourniquet above the wound [1]."
        assertTrue(CheckedAnswer.check("how do I stop severe bleeding from a leg wound", a, listOf(bleeding)).isAnswer)
        // Raw slang misses ("squirting"), the restated question passes.
        val slang = "buddy cut his arm bad blood squirting what do i do"
        assertEquals(Failed.SUBJECT, CheckedAnswer.check(slang, a, listOf(bleeding)).failed)
        assertTrue(CheckedAnswer.check(slang, a, listOf(bleeding), restated = "How do I stop bleeding from an arm wound?").isAnswer)
    }

    @Test fun anInventedNumberBlocksTheAnswer() {
        val d = CheckedAnswer.check("how much lemon juice do I add to a quart of tomatoes when canning",
            "Add 4 tablespoons of bottled lemon juice per quart [1].", listOf(canning))
        assertEquals(Failed.NUMBER, d.failed)
        assertEquals(listOf("4"), d.missing)
        assertEquals(Grounding.NO_ANSWER, d.shown)
    }

    @Test fun citationNumbersAreNotTreatedAsInventedAndNumbersCompareByValue() {
        assertEquals(listOf("1000", "2", "2.5"), CheckedAnswer.numbers("1,000 ml, 2.0 tbsp and 2.5 cups"))
        val p = (1..5).map { Passage(it, 9, "Water", "Drink 2.0 litres of water a day; up to 1,000 mg of salt.") }
        assertTrue(CheckedAnswer.check("how much water a day", "Drink 2 litres of water a day [5].", p).isAnswer)
    }

    /** Returns [reply] for every forced-JSON call, or throws when it is null. */
    private class FakeModel(private val reply: String?) : LocalModel {
        var calls = 0
        override val label = "fake"
        override suspend fun load() {}
        override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int) = kotlinx.coroutines.flow.flowOf("")
        override suspend fun generateJson(systemInstruction: String, userPrompt: String, jsonSchema: String, maxOutputTokens: Int): String {
            calls++
            return reply ?: error("runtime failed")
        }
        override fun lastStats(): GenerationStats? = null
        override fun cancel() {}
        override fun close() {}
    }

    private val lemonQ = "how much lemon juice do I add to a quart of tomatoes when canning"
    private val lemonA = "Add 2 tablespoons of bottled lemon juice per quart of tomatoes [1]."

    @Test fun theJudgeKeepsAnAnswerOnlyOnAClearResponds() = kotlinx.coroutines.runBlocking {
        assertTrue(CheckedAnswer.checkAndJudge(FakeModel("""{"verdict": "responds"}"""), lemonQ, lemonA, listOf(canning)).isAnswer)
        for (reply in listOf("""{"verdict":"off_target"}""", "", "responds", """{"verdict": "maybe"}""", null)) {
            val d = CheckedAnswer.checkAndJudge(FakeModel(reply), lemonQ, lemonA, listOf(canning))
            assertEquals("reply $reply", Failed.OFF_TARGET, d.failed)
            assertEquals(Grounding.NO_ANSWER, d.shown)
        }
    }

    @Test fun theJudgeIsNotAskedWhenTheCodeChecksAlreadyFailed() = kotlinx.coroutines.runBlocking {
        val fake = FakeModel("""{"verdict": "responds"}""")
        val d = CheckedAnswer.checkAndJudge(fake, "what radio channel does the Custer county sheriff use",
            "The NOAA Weather Radio for Custer County, Oklahoma is on 162.525 MHz [1].", listOf(noaa))
        assertEquals(Failed.SUBJECT, d.failed)
        assertEquals(0, fake.calls)
    }

    @Test fun aRestatedPlanIsReadFromTheModelsJson() {
        val p = QueryPlan.parse("""{"standalone_question":"Can a car battery be used as a welding power source?","intent":"can_i","relation":"car battery USED AS welding power source","not_about":"welding \"on\" a car","keyword_query":"car battery welding power source","semantic_query":"x","alternate":"y","life_threat":false}""")
        assertEquals("car battery welding power source", p!!.keyword)
        assertEquals("welding \"on\" a car", p.notAbout)
        assertEquals(null, QueryPlan.parse("""{"standalone_question":"","keyword_query":"x"}"""))
        assertEquals(null, QueryPlan.parse("not json"))
    }

    /** Plan and judge replies by prompt; [judge] answers the judge calls in order. */
    private class ScriptedModel(private val judge: MutableList<Boolean>) : LocalModel {
        override val label = "scripted"
        override suspend fun load() {}
        override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int) = kotlinx.coroutines.flow.flowOf("")
        override suspend fun generateJson(systemInstruction: String, userPrompt: String, jsonSchema: String, maxOutputTokens: Int): String =
            if (systemInstruction == QueryPlan.SYSTEM) """{"standalone_question":"How much lemon juice per quart of canned tomatoes?","keyword_query":"lemon juice quart tomatoes canning"}"""
            else """{"verdict":"${if (judge.removeAt(0)) "responds" else "off_target"}"}"""
        override fun lastStats(): GenerationStats? = null
        override fun cancel() {}
        override fun close() {}
    }

    private fun twoPathsWith(judge: List<Boolean>, rawAnswer: String = lemonA): Pair<CheckedAnswer.Result, List<String>> = kotlinx.coroutines.runBlocking {
        val searched = mutableListOf<String>()
        val restatedSources = listOf(canning.copy(title = "Home Canning (restated search)"))
        val r = CheckedAnswer.twoPaths(ScriptedModel(judge.toMutableList()), lemonQ,
            search = { w, restated -> searched += w; if (restated) restatedSources else listOf(canning) },
            write = { ps -> if (ps === restatedSources) lemonA else rawAnswer })
        r to searched
    }

    @Test fun twoPathsShowsTheRestatedAnswerOnlyWhenBothPathsPass() {
        val (ok, searched) = twoPathsWith(listOf(true, true))
        assertTrue(ok.isAnswer)
        assertEquals(lemonA, ok.shown)
        assertEquals("Home Canning (restated search)", ok.passages.single().title)
        assertEquals(listOf("lemon juice quart tomatoes canning", lemonQ), searched)

        val (restatedFails, searched2) = twoPathsWith(listOf(false))
        assertEquals(Grounding.NO_ANSWER, restatedFails.shown)
        assertEquals("restated", restatedFails.stage)
        assertEquals("the raw path is not run when the first fails", 1, searched2.size)

        val (rawFails, _) = twoPathsWith(listOf(true, false))
        assertEquals(Grounding.NO_ANSWER, rawFails.shown)
        assertEquals("raw", rawFails.stage)

        val (rawInvents, _) = twoPathsWith(listOf(true, true), rawAnswer = "Add 5 tablespoons per quart [1].")
        assertEquals(Grounding.NO_ANSWER, rawInvents.shown)
        assertEquals(Failed.NUMBER, rawInvents.failed)
    }

    // PC run v3 (2026-11-24): doxycycline's row given as amoxicillin's dose; every number was in the passage.
    @Test fun aNumberFromAnotherRowOfTheTableIsBlocked() {
        val table = Passage(1, 9, "USAMRIID-Medical-Management-Biological-Casualties-10th-2025",
            "Selected Antibiotic Dosages for MWDs14\n\nGeneric Name Dose Frequency Route Comments\n\n" +
                "Amikacin 15-30 24 h IV Can be given IV, IM, or\nmg/kg SC once daily.\n\n" +
                "Amoxicillin 11-15 8-12h PO None\nmg/kg\n\nChloramphenicol 40-50 8h PO, Recommend higher end\nmg/kg IV, SC, for BWA\n\nIM\n" +
                "Ciprofloxacin 35 mg/kg 24h IV or Bioavailability variable in\n\nPO dog\n" +
                "Doxycycline 5-10 12h IV or Treatment for 14-28 days\n\nmg/kg PO recommended in most")
        val q = "what dose of amoxicillin should I give my dog"
        val wrong = CheckedAnswer.check(q, "The recommended dose of amoxicillin for dogs is 5-10 mg/kg, given every 12 hours [1].", listOf(table))
        assertEquals(Failed.PAIR, wrong.failed)
        assertEquals(Grounding.NO_ANSWER, wrong.shown)
        // The right row passes when every subject word is near it...
        assertTrue(CheckedAnswer.check("what is the dose of amoxicillin", "Amoxicillin: 11-15 mg/kg every 8-12 hours by mouth [1].", listOf(table)).isAnswer)
        // ...but the table says "MWDs", not "dog", so for this question even the right row is withheld (fails safe).
        assertEquals(Failed.PAIR, CheckedAnswer.check(q, "Amoxicillin for dogs: 11-15 mg/kg every 8-12 hours by mouth [1].", listOf(table)).failed)
    }

    @Test fun stepNumbersAreLayoutNotInventedNumbers() {
        val radio = "1. Apply direct pressure to the wound. 2. If it does not stop, apply a tourniquet above the wound.\n3) Keep pressure on."
        assertTrue(CheckedAnswer.check("how do I stop bleeding from a leg wound", radio, listOf(bleeding)).isAnswer)
        // A real quantity inside a step is still checked.
        val dose = "1. Add 4 tablespoons of bottled lemon juice per quart."
        assertEquals(Failed.NUMBER, CheckedAnswer.check("how much lemon juice do I add to a quart of tomatoes when canning", dose, listOf(canning)).failed)
    }

    // Emulator 2026-11-24: the EPA answer was withheld because the question said "purify".
    @Test fun anOfficialSourcesOwnWordForTheSameThingCounts() {
        val epa = Passage(1, 7, "EPA Emergency Disinfection of Drinking Water",
            "Disinfect water using household bleach: add 8 drops of 6% bleach to each gallon of water.")
        val a = "Add 8 drops of 6% bleach to each gallon of water [1]."
        assertTrue(CheckedAnswer.check("how many drops of bleach to purify a gallon of water", a, listOf(epa)).isAnswer)
        assertTrue(CheckedAnswer.check("how much clorox per gallon to make creek water safe", a,
            listOf(epa.copy(markedText = epa.markedText + " Stream water should be filtered first."))).isAnswer)
        // A different subject is still blocked.
        assertEquals(Failed.SUBJECT, CheckedAnswer.check("how many drops of iodine to purify a gallon of water", a, listOf(epa)).failed)
    }
}
