package com.bastion.terminal.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retrieval evaluation against the offline reference: library + ZIM, ranked
 * together by [Retriever]. Needs a ZIM in BASTION's folder
 * (`Android/media/com.bastion.terminal/zim/`); the cases target WikiMed
 * (`wikipedia_en_medicine_*`) and the test is **skipped** for any other ZIM
 * or none. The library side is an isolated store with the eval fixtures, so
 * the user's library is never touched.
 *
 * A case passes when a chosen passage comes from the expected source and
 * contains at least one of the practical answer phrases — the "how to treat"
 * cases ask for what to *do*, not just that the article was found.
 *
 * Output: one "ZimEval" log line per question plus a summary line.
 */
@RunWith(AndroidJUnit4::class)
class ZimRetrievalEvalTest {

    /** [source] is the exact article (or library document) title. */
    private data class Case(val question: String, val source: String, val anyOf: List<String>, val alsoFrom: List<String> = emptyList())

    private val cases = listOf(
        // Facts / symptoms.
        Case("What are the symptoms of hypothermia", "Hypothermia", listOf("confusion", "shivering")),
        Case("What are the symptoms of carbon monoxide poisoning", "Carbon monoxide poisoning", listOf("headache", "dizziness")),
        Case("What are the signs of dehydration", "Dehydration", listOf("thirst", "urine")),
        Case("What are the early symptoms of Lyme disease", "Lyme disease", listOf("erythema migrans", "rash")),
        Case("What causes altitude sickness", "Altitude sickness", listOf("oxygen", "ascent")),
        // "How to treat X" — must reach the practical paragraph.
        Case("How do I treat a snakebite", "Snakebite", listOf("immobiliz", "immobilis", "antivenom", "keep the person calm")),
        Case("How to treat heat stroke", "Heat stroke", listOf("cool", "ice", "evaporat")),
        Case("How do you treat frostbite", "Frostbite", listOf("rewarm", "warm water")),
        Case("How to treat anaphylaxis", "Anaphylaxis", listOf("epinephrine", "adrenaline")),
        // "Thermal burn" is the article on heat burns specifically — an equally good source.
        Case("First aid for a burn", "Burn", listOf("cool", "running water"), alsoFrom = listOf("Thermal burn")),
        Case("How to treat a sprained ankle", "Sprained ankle", listOf("rest", "ice", "elevat", "compression")),
        Case("What is the compression rate for CPR", "Cardiopulmonary resuscitation", listOf("100", "120")),
        // The user's own library must still win where it holds the answer.
        Case("How much bleach per gallon to treat water", "field-manual", listOf("8 drops")),
        Case("Where does Team Kestrel regroup if separated", "field-manual", listOf("north water tower")),
    )

    @Test
    fun zimRetrievalFindsPracticalPassages() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val info = zim.currentInfo()
        assumeTrue("no ZIM in ${zim.folder()} — skipped", info != null)
        assumeTrue(
            "ZIM ${info!!.fileName} is not WikiMed — cases don't apply, skipped",
            info.fileName.startsWith("wikipedia_en_medicine"),
        )

        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val store = LibraryStore.isolated(ctx, "zim-eval")
        store.importSample()
        store.importBytes(testAssets.open("qa/field-manual.md").use { it.readBytes() }, "field-manual.md", "eval fixture")
        for (i in 1..3) {
            store.importBytes(testAssets.open("qa/distractor-$i.md").use { it.readBytes() }, "distractor-$i.md", "eval fixture")
        }
        val retriever = Retriever(store, zim)

        var hits = 0
        var totalChars = 0
        for (c in cases) {
            val t0 = System.nanoTime()
            val passages = retriever.retrieve(c.question)
            val ms = (System.nanoTime() - t0) / 1_000_000
            // Exact article (ZIM title is "<zim>: <article>"), not "Exercise-induced anaphylaxis" for "Anaphylaxis".
            val fromSource = passages.filter { p ->
                (listOf(c.source) + c.alsoFrom).any { p.title.substringAfter(": ").equals(it, ignoreCase = true) }
            }
            // A "how to treat" question must be answered from a treatment-type section,
            // not a phrase that happens to appear under Research or History.
            val treat = Grounding.aspect(c.question) == Grounding.Aspect.TREATMENT && c.source != "field-manual"
            val ok = fromSource.any { p ->
                c.anyOf.any { p.plainText.contains(it, ignoreCase = true) } &&
                    (!treat || p.heading?.let { Grounding.Aspect.TREATMENT.headings.containsMatchIn(it) } == true)
            }
            if (ok) hits++
            totalChars += passages.sumOf { it.plainText.length }
            Log.i(
                TAG,
                "${if (ok) "HIT " else "MISS"} | ${c.question} | ${ms}ms | " +
                    passages.joinToString(" ; ") { "${it.title.substringAfter(": ")}›${it.heading ?: "-"}" },
            )
            if (!ok) {
                Log.i(TAG, "     articles: ${retriever.lastZimArticles}")
                Log.i(TAG, "     choice: ${retriever.lastChoice}")
                passages.forEach { Log.i(TAG, "     [${it.index}] ${it.label} :: ${it.plainText.take(160).replace('\n', ' ')}") }
            }
        }
        Log.i(TAG, "SUMMARY hits=$hits/${cases.size} avgChars=${totalChars / cases.size} zim=${info.fileName}")
        assertTrue("ZIM retrieval hits $hits/${cases.size} (see ZimEval log)", hits == cases.size)
    }

    private companion object {
        const val TAG = "ZimEval"
    }
}
