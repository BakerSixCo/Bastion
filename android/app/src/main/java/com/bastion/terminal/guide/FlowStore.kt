package com.bastion.terminal.guide

import android.content.Context
import com.bastion.terminal.BuildConfig
import java.io.File

/**
 * The guided flows the phone may show: the owner-approved ones bundled in
 * assets/knowledge/flows.json (written by tools/knowledge/flows.pl), each re-checked by
 * [FlowValidator]. A flow that fails is refused and listed in [Loaded.rejected], never shown.
 *
 * Debug builds only: also flows from the .json files in <externalMediaDirs[0]>/flows-test, marked
 * [Flow.test] (red TEST FLOW banner). Release builds never read that folder.
 */
object FlowStore {
    data class Loaded(val flows: List<Flow>, val rejected: List<String>)

    const val TEST_DIR = "flows-test"

    @Volatile private var bundled: Loaded? = null

    /** Bundled flows (cached) plus, in debug builds, the test folder (read fresh each call). */
    fun load(context: Context): Loaded {
        val b = bundled ?: synchronized(this) { bundled ?: loadBundled(context).also { bundled = it } }
        if (!BuildConfig.DEBUG) return b
        val t = loadTestFolder(context)
        return Loaded(b.flows + t.flows, b.rejected + t.rejected)
    }

    private fun loadBundled(context: Context): Loaded {
        val json = runCatching { context.assets.open("knowledge/flows.json").bufferedReader().use { it.readText() } }
            .getOrNull() ?: return Loaded(emptyList(), emptyList())
        val all = runCatching { FlowParser.parseBundle(json) }
            .getOrElse { return Loaded(emptyList(), listOf("flows.json: not readable (${it.message})")) }
        return accept(all.map { "flows.json" to it }, requireApproved = true)
    }

    private fun loadTestFolder(context: Context): Loaded {
        val dir = context.externalMediaDirs.firstOrNull { it != null }?.let { File(it, TEST_DIR) }
            ?: return Loaded(emptyList(), emptyList())
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return Loaded(emptyList(), emptyList())
        val rejected = mutableListOf<String>()
        val parsed = files.mapNotNull { f ->
            runCatching { f.name to FlowParser.parse(f.readText(), test = true) }
                .onFailure { rejected += "${f.name}: not a readable flow (${it.message})" }.getOrNull()
        }
        val r = accept(parsed, requireApproved = false)
        return Loaded(r.flows, rejected + r.rejected)
    }

    /** Pure: keeps the valid flows (first of each id), reports the rest. */
    fun accept(items: List<Pair<String, Flow>>, requireApproved: Boolean): Loaded {
        val ok = mutableListOf<Flow>(); val bad = mutableListOf<String>(); val ids = HashSet<String>()
        for ((src, f) in items) {
            val p = FlowValidator.problems(f).toMutableList()
            if (requireApproved && f.status != "approved") p += "not approved"
            if (!ids.add(f.id)) p += "duplicate id"
            if (p.isEmpty()) ok += f else bad += "$src: ${f.id}: ${p.joinToString("; ")}"
        }
        return Loaded(ok, bad)
    }
}
