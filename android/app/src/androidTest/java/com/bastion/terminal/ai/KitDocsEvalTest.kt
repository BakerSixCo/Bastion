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
 * Retrieval evaluation for the kit's documents: the regulations, field guides,
 * manuals and station lists copied to BASTION's docs folder, and the PDFs
 * inside document-bundle references. Reads the phone's real library (it
 * changes nothing). **Skipped** unless the kit documents have been imported.
 *
 * A case passes when a chosen passage comes from a document whose title
 * contains [Case.doc] and holds one of the answer phrases.
 *
 * Output: one "KitEval" log line per question plus a summary line.
 */
@RunWith(AndroidJUnit4::class)
class KitDocsEvalTest {

    private data class Case(val question: String, val doc: String, val anyOf: List<String>, val knownGap: String? = null)

    private val cases = listOf(
        Case("what is the daily bag limit for trout in Idaho", "Fishing", listOf("trout limit", "bag limit", "daily limit")),
        Case("when does elk season open in Idaho", "Big-Game", listOf("elk")),
        Case("what frequency is the NOAA weather radio in Boise", "NOAA", listOf("162.550")),
        Case("what NOAA weather radio station covers Valley county Idaho", "NOAA", listOf("Valley County, Idaho")),
        Case("how long do I process quart jars of green beans in a pressure canner", "Canning", listOf("25")),
        Case("how do I can tomatoes safely", "Canning", listOf("lemon juice", "citric acid", "acidif", "Do not can")),
        Case("canning tomatoes do I need to add lemon juice", "Canning", listOf("lemon juice", "citric acid", "acidif")),
        Case("how do I find water in the desert", "", listOf("still", "dew", "condens", "dry stream", "trails often indicate water", "cactus")),
        Case("how do I build a shelter in snow", "Survival", listOf("snow cave", "trench", "insulat")),
        Case("how do I treat a sucking chest wound", "", listOf("occlusive", "seal", "three sides", "airtight")),
        Case("what is huckleberry used for", "Forest", listOf("huckleberr")),
        Case("how do I identify a morel mushroom", "Mushroom", listOf("morel")),
        Case("how do I tie a bowline knot", "Knots", listOf("bowline")),
        Case("how many drops of bleach to purify a gallon of water", "", listOf("drops", "bleach", "chlorine")),
        Case("how do I apply a tourniquet", "", listOf("tourniquet")),
        Case("how do I splint a broken leg", "", listOf("splint")),
        Case("how do I signal for rescue", "", listOf("mirror", "signal fire", "smoke", "whistle", "ground-to-air")),
    )

    @Test
    fun kitDocumentQuestionsFindTheirPassages() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val store = LibraryStore.get(ctx)
        val titles = store.listDocs().map { it.title }
        assumeTrue("needs the kit documents in the library (found ${titles.size}) — skipped",
            titles.any { it.contains("NOAA") } && titles.any { it.contains("Canning") })
        Log.i(TAG, "library: ${titles.size} documents")
        store.listDocs().groupingBy { it.provenance.substringBefore(" · ").take(60) }.eachCount().forEach { (k, v) -> Log.i(TAG, "  $v from: $k") }

        val retriever = Retriever(store, zim)
        var hits = 0
        for (c in cases) {
            val t0 = System.nanoTime()
            val passages = retriever.retrieve(c.question)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val ok = passages.any { p ->
                p.title.contains(c.doc, ignoreCase = true) && c.anyOf.any { p.plainText.contains(it, ignoreCase = true) }
            }
            if (ok && c.knownGap == null) hits++
            Log.i(TAG, "${if (ok) "HIT " else if (c.knownGap != null) "GAP " else "MISS"} | ${c.question} | ${ms}ms")
            passages.forEach { Log.i(TAG, "     [${it.index}] ${it.title} › ${it.heading ?: "-"} :: ${it.plainText.take(200).replace('\n', ' ')}") }
        }
        val counted = cases.count { it.knownGap == null }
        Log.i(TAG, "SUMMARY hits=$hits/$counted knownGaps=${cases.size - counted}")
        assertTrue("kit document retrieval hits $hits/$counted (see KitEval log)", hits == counted)
    }

    private companion object { const val TAG = "KitEval" }
}
