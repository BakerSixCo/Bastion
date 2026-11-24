package com.bastion.terminal.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.data.Scope
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.mesh.MeshAutoResponder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The radio answer path with the real on-device model: what a "?question"
 * sent over the mesh gets back. Same retrieval scope, instructions and length
 * limit as [MeshAutoResponder]. **Skipped** without a model.
 *
 * A question the sources answer passes when the reply is not a refusal and
 * contains one of the expected facts. A question they cannot answer, or only
 * seem to (same words, different question), passes when the reply is a refusal.
 *
 * Output: "MeshEval" log lines, each answer in full, and a summary.
 */
@RunWith(AndroidJUnit4::class)
class MeshAnswerEvalTest {

    /** [anyOf] empty = the right reply is "no answer". */
    private data class Case(val question: String, val anyOf: List<String>)

    private val cases = listOf(
        // Asked from a real phone on 2026-11-23: the sources hold "disconnect the battery when
        // welding ON a car", which is a different question. It was answered with that until the
        // checked-answer pipeline (CheckedAnswer.twoPaths); refused since 2026-11-24 (emulator).
        Case("can I weld with a car battery", emptyList()),
        Case("how do you treat frostbite", listOf("warm")),
        Case("how do I deal with a sucking chest wound", listOf("seal", "occlusive", "dressing", "cover")),
        Case("how do I treat a burn", listOf("cool", "water")),
        Case("how do I jump start a car", listOf("negative", "ground", "cable", "jumper")),
        Case("what are the symptoms of hypothermia", listOf("shiver", "confusion", "slurred")),
        Case("what is tylenol used for", listOf("pain", "fever")),
        Case("how do I change a flat tire", listOf("jack", "lug", "nut", "spare")),
        Case("why is my car overheating", listOf("coolant", "thermostat", "radiator", "fan", "water pump")),
        Case("how do I purify water", listOf("boil", "filter", "chlorin", "bleach")),
        Case("can I drink water from a car radiator", emptyList()),
        Case("can I charge a phone from a lemon", emptyList()),
        Case("who won the 2022 world cup", emptyList()),
    )

    @Test
    fun radioQuestionsGetUsefulAnswersOrAnHonestNo() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val ai = LocalAi.get(ctx)
        ai.refresh()
        val model = ai.obtain()
        assumeTrue("needs an on-device model: skipped", model != null)
        model!!
        Log.i(TAG, "model ${model.label}")
        val only = InstrumentationRegistry.getArguments().getString("only")

        val retriever = Retriever(LibraryStore.get(ctx), zim)
        var pass = 0; var ran = 0
        for (c in cases) {
            if (only != null && !c.question.contains(only, ignoreCase = true)) continue
            ran++
            val t0 = System.nanoTime()
            val answer = MeshAutoResponder.answerFor(retriever, c.question) { model }

            val s = (System.nanoTime() - t0) / 1e9
            val refused = answer == null
            val ok = if (c.anyOf.isEmpty()) refused else !refused && c.anyOf.any { answer!!.contains(it, ignoreCase = true) }
            if (ok) pass++
            Log.i(TAG, "${if (ok) "PASS" else "FAIL"} | ${c.question} | ${"%.0f".format(s)}s | ${if (refused) "refused" else "answered"}")
            Log.i(TAG, "     S: ${retriever.lastChoice.take(700)}")
            (answer ?: "(no answer)").chunked(900).forEach { Log.i(TAG, "     A: ${it.replace('\n', ' ')}") }
        }
        Log.i(TAG, "SUMMARY pass=$pass/$ran model=${model.label}")
    }

    private companion object { const val TAG = "MeshEval" }
}
