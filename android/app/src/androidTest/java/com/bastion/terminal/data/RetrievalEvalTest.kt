package com.bastion.terminal.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retrieval evaluation on a real device/emulator SQLite: does the passage
 * containing the answer reach the model? Uses an isolated store, never the
 * user's library. Fixtures are invented test data (androidTest/assets/qa).
 *
 * Output: one "RetrievalEval" log line per question plus a summary line.
 */
@RunWith(AndroidJUnit4::class)
class RetrievalEvalTest {

    private data class Case(val question: String, val mustContain: List<String>)

    private val cases = listOf(
        Case("Where does Team Kestrel regroup if separated", listOf("north water tower")),
        Case("When are the scheduled radio check-ins", listOf("15 minutes past every even hour")),
        Case("What happens if a radio check-in is missed", listOf("backup channel")),
        Case("At what temperature should spare lithium batteries be stored", listOf("between 15 and 25")),
        Case("How often should stored battery packs be recharged", listOf("every three months")),
        Case("How much bleach per gallon to treat water", listOf("8 drops")),
        Case("How far from the house should the generator be placed", listOf("at least 20 feet")),
        Case("How do I avoid carbon monoxide from a generator", listOf("20 feet")),
        // Needs two different passages of the same long document.
        Case("What are the rally point and the radio schedule", listOf("north water tower", "even hour")),
        Case("How long should I boil water at high elevation", listOf("boil for 3 minutes")),
        // Hard: a 6-step procedure (~110 words) — needs more than a short snippet.
        Case(
            "What are the steps to set up the field antenna",
            listOf("Step 1: choose open high ground", "Step 4: raise the mast", "Step 6: log the frequency"),
        ),
        // Hard: six distractor docs repeat "recommended water barrel"; only the
        // manual has the rare decisive term "bleach".
        Case("What is the recommended bleach dose for a water barrel", listOf("8 drops")),
        Case("Which water barrel treatment uses bleach drops per gallon", listOf("8 drops")),
    )

    @Test
    fun retrievalFindsAnswerPassages() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val store = LibraryStore.isolated(ctx, "retrieval-eval")
        store.importBytes(testAssets.open("sample_field_notes.md").use { it.readBytes() }, "SAMPLE - Bastion field notes.md", "Bundled sample document (demo data, clearly labeled)")
        store.importBytes(testAssets.open("qa/field-manual.md").use { it.readBytes() }, "field-manual.md", "eval fixture")
        store.importBytes(testAssets.open("qa/injection-test.txt").use { it.readBytes() }, "injection-test.txt", "eval fixture")
        for (i in 1..6) {
            store.importBytes(testAssets.open("qa/distractor-$i.md").use { it.readBytes() }, "distractor-$i.md", "eval fixture")
        }

        var hits = 0
        var totalChars = 0
        for (c in cases) {
            val passages = store.retrieve(c.question)
            val text = passages.joinToString("\n") { it.plainText }
            val missing = c.mustContain.filterNot { text.contains(it, ignoreCase = true) }
            val ok = missing.isEmpty()
            if (ok) hits++
            totalChars += text.length
            Log.i(
                TAG,
                "${if (ok) "HIT " else "MISS"} | ${c.question} | passages=${passages.size} " +
                    "chars=${text.length} | docs=${passages.map { it.title }}" +
                    if (missing.isNotEmpty()) " | missing=$missing" else "",
            )
            if (!ok) Log.i(TAG, "     terms: ${store.termStats(c.question)}")
            if (!ok) passages.forEach {
                Log.i(TAG, "     [${it.index}] ${it.label} :: ${it.plainText.take(90).replace('\n', ' ')}")
            }
        }
        Log.i(TAG, "SUMMARY hits=$hits/${cases.size} avgChars=${totalChars / cases.size}")
        assertTrue("retrieval hits $hits/${cases.size} (see RetrievalEval log)", hits == cases.size)
    }

    private companion object {
        const val TAG = "RetrievalEval"
    }
}
