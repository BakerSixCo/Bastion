package com.bastion.terminal.mesh

import com.bastion.terminal.ai.CardStore
import com.bastion.terminal.mesh.MeshAutoResponder.CardMatch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadioCardCodeEvalTest {
    @Test
    fun codeOnlyMatchingDoesNotSendTheWrongCard() = runBlocking<Unit> {
        val repo = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "tools/model-eval/radio-questions.json").isFile }
        val cardFiles = File(repo, "tools/knowledge/cards").listFiles { file -> file.extension == "json" }
            .orEmpty().sortedBy { it.name }
        val cards = CardStore.parse(JSONObject().put("cards", JSONArray(cardFiles.map { JSONObject(it.readText()) })).toString())
        val cardIds = cards.map { it.id }.toSet()
        assertTrue("load the full set of real cards", cardIds.size >= 55)
        val cases = JSONObject(File(repo, "tools/model-eval/radio-questions.json").readText()).getJSONArray("cases")
        assertTrue("at least 80 cases", cases.length() >= 80)
        var right = 0
        var menu = 0
        var notCovered = 0
        var noneCases = 0
        val wrong = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val question = case.getString("question")
            val expected = case.getString("expected")
            if (expected == "none") noneCases++ else assertTrue("unknown expected card $expected", expected in cardIds)
            when (val decision = MeshAutoResponder.matchCard(question, cards)) {
                is CardMatch.Found -> if (decision.card.id == expected) right++ else {
                    wrong += "$question | expected=$expected | picked=${decision.card.id}"
                }
                is CardMatch.Menu -> menu++
                CardMatch.NotCovered -> notCovered++
            }
        }
        println("RadioEval code-only cards=${cards.size} cases=${cases.length()} " +
            "right-card=$right wrong-card=${wrong.size} menu=$menu not-covered=$notCovered")
        assertTrue("at least 20 unmatched cases", noneCases >= 20)
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }
}
