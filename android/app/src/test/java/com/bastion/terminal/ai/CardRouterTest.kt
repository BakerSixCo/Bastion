package com.bastion.terminal.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cards only (not real content): real cards live in tools/knowledge and are owner-approved. */
class CardRouterTest {

    private fun card(id: String, title: String, asks: List<String>, answer: List<String>, kind: String = "fact") =
        Card(id, kind, title, asks, answer, emptyList(), listOf(Card.Quote("doc.pdf", answer.joinToString(" "))))

    private val boil = card("water-boil", "How long to boil water to make it safe",
        listOf("how long do I boil water", "boil creek water how long", "is boiling enough"),
        listOf("Bring the water to a rolling boil for 1 minute.", "Above 5,000 feet, boil for 3 minutes."))
    private val weldOnCar = card("weld-on-car", "Welding on a car: protect the electronics",
        listOf("disconnect battery before welding on car", "welding on a vehicle ECU", "arc welding car body"),
        listOf("Disconnect the battery before electric welding on a vehicle."))
    private val tourniquet = card("tourniquet", "Severe bleeding from an arm or leg", listOf("blood spurting from leg", "how to stop bad bleeding on arm", "where to put a tourniquet"),
        listOf("Apply firm direct pressure.", "If it does not stop, apply a tourniquet 2 to 3 inches above the wound."), kind = "protocol")
    private val cards = listOf(boil, weldOnCar, tourniquet)

    /** Answers the router's forced-JSON call with [reply]; counts calls. */
    private class Picker(private val reply: String?) : LocalModel {
        var calls = 0
        override val label = "picker"
        override suspend fun load() {}
        override fun generate(systemInstruction: String, userPrompt: String, maxOutputTokens: Int) = flowOf("")
        override suspend fun generateJson(systemInstruction: String, userPrompt: String, jsonSchema: String, maxOutputTokens: Int): String {
            calls++; return reply ?: error("runtime failed")
        }
        override fun lastStats(): GenerationStats? = null
        override fun cancel() {}
        override fun close() {}
    }

    @Test fun candidatesAreFoundByTheQuestionsWords() {
        assertEquals("water-boil", CardRouter.candidates("how long should I boil water from a stream", cards).first().id)
        assertTrue(CardRouter.candidates("who won the world cup", cards).isEmpty())
    }

    @Test fun aChosenCardIsShownOnlyWhenItsSubjectMatches() = runBlocking {
        assertEquals(boil, CardRouter.route(Picker("""{"choice": 1}"""), "how long do I boil water", cards))
        // The welding trap: even if the model picks the welding-on-a-car card, "weld with a car battery"
        // shares every word, so this is what the model's own judgement must catch — but a card whose
        // subject words are missing is refused by code regardless of the model.
        assertNull(CardRouter.route(Picker("""{"choice": 1}"""), "how long do I boil milk", cards))
    }

    @Test fun noneOrAnyDoubtFallsBackToTheCheckedPipeline() = runBlocking {
        for (reply in listOf("""{"choice": 0}""", """{"choice": 9}""", "", "not json", null)) {
            assertNull("reply $reply", CardRouter.route(Picker(reply), "how long do I boil water", cards))
        }
        val p = Picker("""{"choice": 1}""")
        assertNull(CardRouter.route(p, "what is the capital of France", cards))
        assertEquals("no candidates, no model call", 0, p.calls)
    }

    @Test fun slangReachesTheCardThroughTheRestatedQuestion() = runBlocking {
        val slang = "buddy got cut blood squirting outta his leg"
        assertFalse(CardRouter.subjectMatches(slang, tourniquet))
        val restated = "How do I stop severe bleeding from a leg?"
        assertEquals(tourniquet, CardRouter.route(Picker("""{"choice": 1}"""), slang, cards, restated))
    }

    @Test fun theCardTextIsExactlyTheApprovedLinesNumberedForProtocols() {
        assertEquals("1. Apply firm direct pressure.\n2. If it does not stop, apply a tourniquet 2 to 3 inches above the wound.", CardRouter.text(tourniquet))
        assertEquals("Bring the water to a rolling boil for 1 minute.\nAbove 5,000 feet, boil for 3 minutes.", CardRouter.text(boil))
        assertTrue(CardRouter.schema(3).contains("[0,1,2,3]"))
    }
}
