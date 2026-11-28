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
    fun tuningCorpusHasNoWrongCodeOnlyMatches() = runBlocking<Unit> {
        evaluate("radio-questions.json", minimumCases = 80, minimumNone = 20, reportTargets = true)
    }

    @Test
    fun holdoutCorpusHasNoWrongCodeOnlyMatches() = runBlocking<Unit> {
        evaluate("radio-questions-holdout.json", minimumCases = 40, minimumNone = 12)
    }

    private suspend fun evaluate(
        filename: String,
        minimumCases: Int,
        minimumNone: Int,
        reportTargets: Boolean = false,
    ) {
        val repo = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "tools/knowledge/cards").isDirectory }
        val cardFiles = File(repo, "tools/knowledge/cards").listFiles { file -> file.extension == "json" }
            .orEmpty().sortedBy { it.name }
        val cards = CardStore.parse(JSONObject().put("cards", JSONArray(cardFiles.map { JSONObject(it.readText()) })).toString())
        val cardIds = cards.map { it.id }.toSet()
        assertTrue("load the full set of real cards", cardFiles.size >= 55)
        assertEquals("parse every source card with a unique ID", cardFiles.size, cardIds.size)
        val cases = JSONObject(File(repo, "tools/model-eval/$filename").readText()).getJSONArray("cases")
        assertTrue("$filename: at least $minimumCases cases", cases.length() >= minimumCases)
        var right = 0
        var menuWithExpected = 0
        var menuWithoutExpected = 0
        var menuOnNone = 0
        var notCovered = 0
        var noneCases = 0
        val wrong = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val question = case.getString("question")
            val expected = case.getString("expected")
            if (expected == "none") noneCases++ else assertTrue("$filename: unknown expected card $expected", expected in cardIds)
            when (val decision = MeshAutoResponder.matchCard(question, cards)) {
                is CardMatch.Found -> if (decision.card.id == expected) right++ else {
                    wrong += "case ${i + 1} | expected=$expected | picked=${decision.card.id}"
                }
                is CardMatch.Menu -> when {
                    expected == "none" -> menuOnNone++
                    decision.cards.any { it.id == expected } -> menuWithExpected++
                    else -> menuWithoutExpected++
                }
                CardMatch.NotCovered -> notCovered++
            }
        }
        println(
            "RadioEval code-only corpus=$filename cards=${cards.size} cases=${cases.length()} " +
                "right-card=$right wrong-card=${wrong.size} menu-with-expected=$menuWithExpected " +
                "menu-without-expected=$menuWithoutExpected menu-on-none=$menuOnNone not-covered=$notCovered",
        )
        if (reportTargets) {
            println(
                "RadioEval tuning targets right-or-menu-with-expected=${right + menuWithExpected}/${cases.length() - noneCases} " +
                    "(target at least 45), menu-on-none=$menuOnNone/$noneCases (target at most 5)",
            )
        }
        assertTrue("$filename: at least $minimumNone unmatched cases", noneCases >= minimumNone)
        assertEquals("$filename:\n${wrong.joinToString("\n")}", 0, wrong.size)
    }
}
