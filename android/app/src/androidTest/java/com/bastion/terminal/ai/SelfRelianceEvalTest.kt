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
 * Retrieval evaluation for the "self-reliance" references: repair, growing
 * food, water and low-tech building, asked the way someone would ask when
 * services are down. Needs the three kit references next to WikiMed
 * (`tools/kit-manifest.json`, preset "self-reliance"): Motor Vehicle
 * Mechanics Q&A, Appropedia and Gardening Q&A. **Skipped** if any is missing.
 *
 * A case passes when a chosen passage comes from the expected reference and
 * contains one of the practical answer phrases. The library side is an empty
 * isolated store, so the user's library is never touched.
 *
 * Output: one "SelfEval" log line per question plus a summary line.
 */
@RunWith(AndroidJUnit4::class)
class SelfRelianceEvalTest {

    /** [zim]: a word from the reference's title the passage must come from. */
    /** [knownGap]: why this case is not expected to pass yet; it is run and logged but not counted. */
    private data class Case(val question: String, val zim: String, val anyOf: List<String>, val knownGap: String? = null)

    private val cases = listOf(
        Case("how do I repair a blown head gasket in a 2009 jeep wrangler", "Mechanics", listOf("head gasket")),
        Case("my car just clicks and won't start", "Mechanics", listOf("battery", "starter", "solenoid")),
        Case("how do I jump start a car", "Mechanics", listOf("jumper", "negative", "ground", "cables")),
        Case("how do I change brake pads", "Mechanics", listOf("caliper", "piston", "rotor")),
        Case("how do I bleed air out of brake lines", "Mechanics", listOf("bleed", "bleeder")),
        Case("how do I build a slow sand filter for drinking water", "Appropedia", listOf("sand", "gravel", "biolayer", "schmutzdecke")),
        Case("how do I collect rainwater from a roof", "Appropedia", listOf("gutter", "first flush", "catchment", "tank")),
        Case("how do I build a composting toilet", "Appropedia", listOf("sawdust", "urine", "chamber", "compost")),
        Case("how do I build a rocket stove", "Appropedia", listOf("combustion chamber", "chimney", "elbow", "insulat")),
        Case("how do I disinfect water with sunlight", "Appropedia", listOf("bottle", "SODIS", "six hours", "6 hours", "UV")),
        Case("why are my tomato leaves turning yellow", "Gardening", listOf("nitrogen", "overwater", "water", "blight")),
        Case("how do I get rid of aphids", "Gardening", listOf("soap", "ladybug", "ladybird", "neem", "hose", "spray")),
        Case("how do I save tomato seeds", "Gardening", listOf("ferment", "dry", "gel"),
            knownGap = "the reference's own search returns variety-specific threads first; the how-to thread is not in its top results"),
        Case("when should I plant potatoes", "Gardening", listOf("frost", "spring", "soil")),
        // Any reference: WikiMed carries the full Wikipedia "Compost" article, which answers it.
        Case("how do I start a compost pile", "", listOf("green", "brown", "carbon", "nitrogen")),
    )

    @Test
    fun selfRelianceQuestionsFindPracticalPassages() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val files = zim.currentInfos().map { it.fileName }
        val needed = listOf("mechanics.stackexchange", "appropedia", "gardening.stackexchange")
        assumeTrue("needs the self-reliance references (found $files) — skipped",
            needed.all { n -> files.any { it.startsWith(n) } })
        Log.i(TAG, "references: " + zim.currentInfos().joinToString { "${it.fileName}=${it.title}" })

        val retriever = Retriever(LibraryStore.isolated(ctx, "self-eval"), zim)
        var hits = 0
        for (c in cases) {
            val t0 = System.nanoTime()
            val passages = retriever.retrieve(c.question)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val ok = passages.any { p ->
                p.title.substringBefore(": ").contains(c.zim, ignoreCase = true) &&
                    c.anyOf.any { p.plainText.contains(it, ignoreCase = true) }
            }
            if (ok && c.knownGap == null) hits++
            Log.i(TAG, "${if (ok) "HIT " else if (c.knownGap != null) "GAP " else "MISS"} | ${c.question} | ${ms}ms")
            Log.i(TAG, "     articles: ${retriever.lastZimArticles}")
            passages.forEach { Log.i(TAG, "     [${it.index}] ${it.title} › ${it.heading ?: "-"} :: ${it.plainText.take(220).replace('\n', ' ')}") }
        }
        val counted = cases.count { it.knownGap == null }
        Log.i(TAG, "SUMMARY hits=$hits/$counted knownGaps=${cases.size - counted}")
        assertTrue("self-reliance retrieval hits $hits/$counted (see SelfEval log)", hits == counted)
    }

    private companion object {
        const val TAG = "SelfEval"
    }
}
