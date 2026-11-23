package com.bastion.terminal.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answer evaluation: the whole Ask path with the real on-device model —
 * retrieve, prompt, generate — on questions someone would really ask. It
 * measures the model and its instructions, so a prompt or model change can be
 * compared with the run before it. Reads the phone's real library and
 * references; **skipped** without a model or the kit documents.
 *
 * A question that the sources answer passes when the answer is not a refusal,
 * cites only sources that exist, cites at least one, and contains one of the
 * expected facts. A question the sources cannot answer passes when the model
 * refuses. Slow: minutes on an emulator.
 *
 * Output: "AnswerEval" log lines — each answer in full, and a summary.
 */
@RunWith(AndroidJUnit4::class)
class AnswerEvalTest {

    /** [anyOf] empty = the sources cannot answer this; the model should say so. */
    private data class Case(val question: String, val anyOf: List<String>)

    private val cases = listOf(
        Case("what frequency is the NOAA weather radio in Boise", listOf("162.550")),
        Case("what NOAA weather radio station covers Valley county Idaho", listOf("McCall", "162.475")),
        Case("how many drops of bleach to purify a gallon of water", listOf("drops", "teaspoon")),
        Case("how long do I process quart jars of green beans in a pressure canner", listOf("25 min", "25 minutes")),
        Case("how do I treat a sucking chest wound", listOf("seal", "occlusive", "dressing", "cover")),
        Case("what are the symptoms of hypothermia", listOf("shiver")),
        Case("how do I jump start a car", listOf("negative", "ground", "cable", "jumper")),
        Case("how do I get rid of aphids", listOf("soap", "water", "neem", "ladybug", "ladybird")),
        Case("how do I disinfect water with sunlight", listOf("bottle", "hours", "sun")),
        Case("how do I tie a bowline knot", listOf("loop")),
        Case("how long do I process pint jars of green beans in a pressure canner", listOf("20 min", "20 minutes")),
        Case("how much lemon juice do I add to a quart of tomatoes when canning", listOf("2 tablespoons", "two tablespoons")),
        Case("how do I can tomatoes safely", listOf("lemon juice", "citric acid", "acid")),
        Case("what is tylenol used for", listOf("pain", "fever")),
        // The USDA guide has no entry for broccoli: the model must not borrow another vegetable's time.
        Case("how long do I pressure can quart jars of broccoli", emptyList()),
        Case("who won the 2022 world cup", emptyList()),
        Case("what is the wifi password at the Stanley library", emptyList()),
        Case("what is the phone number of the Boise weather radio station", emptyList()),
    )

    @Test
    fun realQuestionsGetGroundedAnswers() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val store = LibraryStore.get(ctx)
        assumeTrue("needs the kit documents — skipped", store.listDocs().any { it.title.contains("NOAA") })
        val ai = LocalAi.get(ctx)
        ai.refresh()
        val model = ai.obtain()
        assumeTrue("needs an on-device model — skipped", model != null)
        model!!
        Log.i(TAG, "model ${model.label}")

        val retriever = Retriever(store, zim)
        var pass = 0; var seconds = 0.0
        for (c in cases) {
            val passages = retriever.retrieve(c.question)
            val sb = StringBuilder()
            val t0 = System.nanoTime()
            if (passages.isNotEmpty()) {
                runCatching {
                    model.generate(Grounding.SYSTEM_INSTRUCTION, Grounding.userPrompt(c.question, passages), AskSession.MAX_ANSWER_TOKENS)
                        .collect { sb.append(it) }
                }.onFailure { sb.append("[generation failed: ${it.message}]") }
            }
            val s = (System.nanoTime() - t0) / 1e9; seconds += s
            val answer = sb.toString().trim()
            val check = Grounding.checkCitations(answer, passages)
            val refused = passages.isEmpty() || check.isNoAnswer
            val ok = if (c.anyOf.isEmpty()) refused else
                !refused && check.invalid.isEmpty() && check.cited.isNotEmpty() && c.anyOf.any { answer.contains(it, ignoreCase = true) }
            if (ok) pass++
            val why = when {
                ok -> ""
                c.anyOf.isEmpty() -> "answered a question the sources cannot answer"
                refused -> "refused"
                check.cited.isEmpty() -> "no citation"
                check.invalid.isNotEmpty() -> "cited sources that do not exist ${check.invalid}"
                else -> "expected fact missing"
            }
            Log.i(TAG, "${if (ok) "PASS" else "FAIL"} | ${c.question} | ${"%.0f".format(s)}s | sources=${passages.size} cited=${check.cited}${if (check.tables.isNotEmpty()) " TABLE-NOTE" else ""} $why")
            answer.chunked(900).forEach { Log.i(TAG, "     A: ${it.replace('\n', ' ')}") }
        }
        Log.i(TAG, "SUMMARY pass=$pass/${cases.size} model=${model.label} seconds=${"%.0f".format(seconds)}")
    }

    private companion object { const val TAG = "AnswerEval" }
}
