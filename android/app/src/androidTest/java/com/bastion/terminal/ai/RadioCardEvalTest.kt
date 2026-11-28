package com.bastion.terminal.ai

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.mesh.MeshAutoResponder
import com.bastion.terminal.mesh.MeshAutoResponder.CardMatch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Match both radio question sets against test-only copies of all source cards.
 * Stage bastion-radio-cards.json, bastion-radio-questions.json and bastion-radio-questions-holdout.json
 * under /data/local/tmp. The test reads those files as shell and keeps the cards local to the test.
 * Pass -e codeOnly true to run the production matcher without loading a model.
 */
@RunWith(AndroidJUnit4::class)
class RadioCardEvalTest {
    @Test
    fun matchTuningCorpusWithTheLocalModel() = runBlocking<Unit> {
        evaluate("radio-questions.json", minimumCases = 80, minimumNone = 20)
    }

    @Test
    fun matchHoldoutCorpusWithTheLocalModel() = runBlocking<Unit> {
        evaluate("radio-questions-holdout.json", minimumCases = 40, minimumNone = 12)
    }

    private suspend fun evaluate(filename: String, minimumCases: Int, minimumNone: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val codeOnly = InstrumentationRegistry.getArguments().getString("codeOnly") == "true"
        fun readStaged(name: String): String {
            val file = "/data/local/tmp/$name"
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $file")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                .also { check(it.startsWith("{")) { "stage $file before running RadioCardEvalTest" } }
        }

        val cards = CardStore.parse(readStaged("bastion-radio-cards.json"))
        val cardIds = cards.map { it.id }.toSet()
        val cases = JSONObject(readStaged("bastion-$filename")).getJSONArray("cases")
        check(cardIds.size >= 55 && cardIds.size == cards.size) { "stage the full set of unique real cards" }
        check(cases.length() >= minimumCases) { "stage the complete $filename corpus" }
        val ai = if (codeOnly) null else LocalAi.get(instrumentation.targetContext)
        if (ai != null) assumeTrue("needs an imported on-device model", ai.currentModelFile() != null)
        var model: LocalModel? = null
        var modelRequests = 0
        var right = 0
        var wrong = 0
        var menuWithExpected = 0
        var menuWithoutExpected = 0
        var menuOnNone = 0
        var notCovered = 0
        var noneCases = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val question = case.getString("question")
            val expected = case.getString("expected")
            if (expected == "none") noneCases++ else check(expected in cardIds) {
                "$filename: unknown expected card $expected"
            }
            when (val decision = MeshAutoResponder.matchCard(question, cards) {
                if (ai == null) return@matchCard null
                modelRequests++
                if (modelRequests == 1) model = ai.obtain()
                model
            }) {
                is CardMatch.Found -> if (decision.card.id == expected) right++ else {
                    wrong++
                    Log.i(TAG, "WRONG corpus=$filename case=${i + 1} expected=$expected picked=${decision.card.id}")
                }
                is CardMatch.Menu -> when {
                    expected == "none" -> menuOnNone++
                    decision.cards.any { it.id == expected } -> menuWithExpected++
                    else -> menuWithoutExpected++
                }
                CardMatch.NotCovered -> notCovered++
            }
        }
        val mode = if (codeOnly) "code-only" else "local-model"
        Log.i(
            TAG,
            "SUMMARY mode=$mode corpus=$filename cards=${cards.size} cases=${cases.length()} right-card=$right wrong-card=$wrong " +
                "menu-with-expected=$menuWithExpected menu-without-expected=$menuWithoutExpected " +
                "menu-on-none=$menuOnNone not-covered=$notCovered model=${model?.label} model-requests=$modelRequests",
        )
        check(noneCases >= minimumNone) { "$filename needs at least $minimumNone unmatched cases" }
        if (codeOnly) check(wrong == 0) { "$filename: $wrong wrong code-only matches" }
        check(modelRequests == 0 || model != null) { "The local model did not load: ${ai?.state?.value}" }
    }

    private companion object { const val TAG = "RadioEval" }
}
