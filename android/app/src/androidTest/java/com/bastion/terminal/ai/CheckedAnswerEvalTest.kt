package com.bastion.terminal.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Ask path exactly as the app runs it, on the real on-device model, over a question file:
 * [CheckedAnswer.twoPaths]: restate → restated search → answer → checks + judge → raw search → answer → checks + judge. Every stage is
 * recorded so the result can be read and scored on the PC (tools/model-eval/devscore.pl).
 *
 *   -e questions questions-v2.json   (in the app's media folder; required, else skipped)
 *   -e ids "m0.*|v07"                 (optional regex on case ids)
 *
 * Appends one JSON line per question to checked-<questions>.jsonl in the media folder and skips
 * ids already there, so a long run can be resumed. **Skipped** without a model.
 */
@RunWith(AndroidJUnit4::class)
class CheckedAnswerEvalTest {

    @Test
    fun askPathWithChecksOnAQuestionFile() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("questions")
        assumeTrue("needs -e questions <file in the media folder>: skipped", name != null)
        val media = ctx.externalMediaDirs.first()
        val ids = args.getString("ids")?.let(::Regex)
        val zim = ZimLibrary.get(ctx)
        zim.restore()
        val ai = LocalAi.get(ctx)
        ai.refresh()
        val model = ai.obtain()
        assumeTrue("needs an on-device model: skipped", model != null)
        model!!
        Log.i(TAG, "model ${model.label}")

        val out = File(media, "checked-" + name!!.removeSuffix(".json") + ".jsonl")
        val done = if (out.exists()) out.readLines().mapNotNull { runCatching { JSONObject(it).getString("id") }.getOrNull() }.toSet() else emptySet()
        val cases = JSONObject(File(media, name).readText()).getJSONArray("cases")
        val retriever = Retriever(LibraryStore.get(ctx), zim)
        var ran = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            if (id in done || (ids != null && !ids.matches(id))) continue
            val q = c.getString("question")
            val t0 = System.nanoTime()
            val stages = org.json.JSONArray()
            val drafts = org.json.JSONArray()
            val result = CheckedAnswer.twoPaths(
                model, q,
                search = { wording, _ -> retriever.retrieve(wording) },
                write = { ps ->
                    val sb = StringBuilder()
                    model.generate(Grounding.SYSTEM_INSTRUCTION, Grounding.userPrompt(q, ps), AskSession.MAX_ANSWER_TOKENS).collect { sb.append(it) }
                    drafts.put(sb.toString()); sb.toString()
                },
                onStage = { stages.put(it) },
            )
            val secs = (System.nanoTime() - t0) / 1e9
            val o = JSONObject()
                .put("id", id).put("cat", c.optString("cat")).put("question", q)
                .put("sources", org.json.JSONArray(result.passages.map { it.label }))
                .put("stages", stages).put("drafts", drafts)
                .put("stage", result.stage).put("failed", result.failed?.name ?: JSONObject.NULL)
                .put("missing", org.json.JSONArray(result.missing))
                .put("shown", result.shown).put("seconds", secs)
            out.appendText(o.toString() + "\n")
            ran++
            Log.i(TAG, "$id | ${result.stage} ${result.failed ?: "SHOWN"} | ${"%.0f".format(secs)}s | $q")
        }
        Log.i(TAG, "DONE ran=$ran file=${out.absolutePath}")
    }

    private companion object { const val TAG = "CheckedEval" }
}
