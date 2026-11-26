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
 * Match test-only copies of all source cards against tools/model-eval/radio-questions.json with the
 * selected on-device model. Stage the two files under /data/local/tmp as described in the report.
 * The test reads those files as shell and never passes its cards to the production store.
 */
@RunWith(AndroidJUnit4::class)
class RadioCardEvalTest {
    @Test
    fun matchRadioQuestionsWithTheLocalModel() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun readStaged(name: String): String {
            val file = "/data/local/tmp/$name"
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $file")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
                .also { assumeTrue("stage $file before running RadioCardEvalTest", it.startsWith("{")) }
        }

        val cards = CardStore.parse(readStaged("bastion-radio-cards.json"))
        val cases = JSONObject(readStaged("bastion-radio-questions.json")).getJSONArray("cases")
        check(cards.map { it.id }.toSet().size >= 55) { "stage the full set of real cards" }
        check(cases.length() >= 80) { "stage the complete radio question corpus" }
        val ai = LocalAi.get(instrumentation.targetContext)
        assumeTrue("needs an imported on-device model", ai.currentModelFile() != null)
        var model: LocalModel? = null
        var modelRequests = 0
        var right = 0
        var wrong = 0
        var menu = 0
        var notCovered = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val question = case.getString("question")
            val expected = case.getString("expected")
            when (val decision = MeshAutoResponder.matchCard(question, cards) {
                modelRequests++
                if (modelRequests == 1) model = ai.obtain()
                model
            }) {
                is CardMatch.Found -> if (decision.card.id == expected) right++ else {
                    wrong++
                    Log.i(TAG, "WRONG | $question | expected=$expected | picked=${decision.card.id}")
                }
                is CardMatch.Menu -> menu++
                CardMatch.NotCovered -> notCovered++
            }
        }
        Log.i(TAG, "SUMMARY cards=${cards.size} cases=${cases.length()} right-card=$right wrong-card=$wrong " +
            "menu=$menu not-covered=$notCovered model=${model?.label} model-requests=$modelRequests")
        check(modelRequests == 0 || model != null) { "The local model did not load: ${ai.state.value}" }
    }

    private companion object { const val TAG = "RadioEval" }
}
