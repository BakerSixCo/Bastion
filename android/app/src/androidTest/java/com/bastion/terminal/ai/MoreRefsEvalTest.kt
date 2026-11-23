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
 * Retrieval evaluation for the kit's larger references, a few real questions
 * each. They do not all fit on a small phone at once, so each case runs only
 * when its reference is loaded and is logged as SKIP otherwise; the test is
 * **skipped** when none is. A case passes when a chosen passage comes from the
 * expected reference and holds one of the answer phrases. The library side is
 * an empty isolated store.
 *
 * Output: "RefEval" log lines — one per question, one per reference, a summary.
 */
@RunWith(AndroidJUnit4::class)
class MoreRefsEvalTest {

    /** [file]: how the reference's file name starts. */
    private data class Case(val file: String, val question: String, val anyOf: List<String>, val knownGap: String? = null)

    private val cases = listOf(
        Case("outdoors.stackexchange", "how do I hang a bear bag", listOf("branch", "rope", "feet", "tree")),
        Case("outdoors.stackexchange", "how do I treat blisters while hiking", listOf("moleskin", "tape", "drain", "sock")),
        Case("outdoors.stackexchange", "what should I do if I meet a mountain lion", listOf("run", "big", "eye contact", "back away", "fight")),
        Case("ham.stackexchange", "how long should a dipole antenna be for 40 meters", listOf("468", "half", "wavelength", "feet"),
            knownGap = "finds dipole tuning threads, not the one that gives the length formula"),
        Case("ham.stackexchange", "what is SWR and why does it matter", listOf("standing wave", "reflected", "match")),
        Case("cooking.stackexchange", "how long can cooked rice sit out safely", listOf("hours", "bacillus", "refrigerat")),
        Case("cooking.stackexchange", "what can I substitute for eggs in baking", listOf("applesauce", "flax", "banana", "yogurt")),
        Case("woodworking.stackexchange", "how do I sharpen a chisel", listOf("stone", "bevel", "grit", "hon")),
        Case("sustainability.stackexchange", "how do I heat a house with less energy", listOf("insulat", "draft", "seal", "heat pump")),
        Case("energypedia", "how do I size a solar panel system for a home", listOf("load", "watt", "battery", "demand")),
        Case("energypedia", "how does a biogas digester work", listOf("anaerobic", "methane", "manure", "slurry"),
            knownGap = "finds the maintenance and types pages, not the page that explains the process"),
        Case("wwwnc.cdc.gov", "how do I prevent malaria when travelling", listOf("prophylaxis", "mosquito", "net", "repellent")),
        Case("wwwnc.cdc.gov", "how do I treat travellers diarrhea", listOf("fluid", "rehydrat", "antibiotic", "loperamide")),
        // Military Medicine is a bundle of PDFs: BASTION indexes it into the library, so it is covered there, not here.
        Case("medlineplus", "what are the warning signs of a stroke", listOf("face", "arm", "speech", "sudden")),
        Case("medlineplus", "what is a normal blood pressure reading", listOf("120", "systolic")),
        Case("medlineplus", "how do I treat a fever in a child", listOf("acetaminophen", "ibuprofen", "fluids")),
        Case("diy.stackexchange", "how do I fix a leaking pipe joint", listOf("solder", "fitting", "compression", "tape", "tighten")),
        Case("diy.stackexchange", "how do I wire a generator to my house safely", listOf("transfer switch", "interlock", "backfeed")),
        Case("diy.stackexchange", "why does my circuit breaker keep tripping", listOf("overload", "short", "amp", "ground fault")),
        Case("ifixit", "how do I replace a phone battery", listOf("adhesive", "battery", "connector", "pry")),
        Case("ifixit", "how do I replace a bicycle inner tube", listOf("tire lever", "tube", "valve", "rim")),
        Case("ifixit", "how do I change a lawn mower spark plug", listOf("spark plug", "socket", "gap")),
        Case("nhs.uk", "how much paracetamol can an adult take", listOf("500mg", "4 hours", "8 tablets", "24 hours")),
        Case("nhs.uk", "who should not take ibuprofen", listOf("ulcer", "pregnan", "asthma", "allerg")),
    )

    @Test
    fun largerReferencesAnswerRealQuestions() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val infos = zim.currentInfos()
        val present = cases.map { it.file }.distinct().filter { f -> infos.any { it.fileName.startsWith(f) } }
        assumeTrue("needs at least one of the larger references (found ${infos.map { it.fileName }}) — skipped", present.isNotEmpty())
        infos.forEach { Log.i(TAG, "reference ${it.fileName} = “${it.title}” ${it.articleCount} articles") }
        (zim.state.value as? com.bastion.terminal.kiwix.ZimState.Ready)?.problems?.forEach { Log.i(TAG, "problem $it") }

        val retriever = Retriever(LibraryStore.isolated(ctx, "ref-eval"), zim)
        val perRef = LinkedHashMap<String, IntArray>()
        for (c in cases) {
            val info = infos.firstOrNull { it.fileName.startsWith(c.file) }
            if (info == null) { Log.i(TAG, "SKIP | ${c.file} | ${c.question}"); continue }
            val t0 = System.nanoTime()
            val passages = retriever.retrieve(c.question)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val ok = passages.any { p -> p.title.startsWith(info.title) && c.anyOf.any { p.plainText.contains(it, ignoreCase = true) } }
            if (c.knownGap == null) { val n = perRef.getOrPut(c.file) { IntArray(2) }; n[1]++; if (ok) n[0]++ }
            Log.i(TAG, "${if (ok) "HIT " else if (c.knownGap != null) "GAP " else "MISS"} | ${c.file} | ${c.question} | ${ms}ms")
            passages.forEach { Log.i(TAG, "     [${it.index}] ${it.title} › ${it.heading ?: "-"} :: ${it.plainText.take(160).replace('\n', ' ')}") }
        }
        perRef.forEach { (f, n) -> Log.i(TAG, "REF $f ${n[0]}/${n[1]}") }
        val hits = perRef.values.sumOf { it[0] }; val total = perRef.values.sumOf { it[1] }
        Log.i(TAG, "SUMMARY hits=$hits/$total references=${perRef.size}")
        assertTrue("larger references hit $hits/$total (see RefEval log)", hits == total)
    }

    private companion object { const val TAG = "RefEval" }
}
