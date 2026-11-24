package com.bastion.terminal.ai

import android.app.ActivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.kiwix.ZimLibrary
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Baseline measurements on the device itself, with the model file and runtime the app
 * really uses. Nothing here changes how the app answers; it only measures.
 *
 * - [answersOnTheFixedSet]: the real model on the fixed evaluation set
 *   (`evalset-fixed.json`, pushed into the app's media folder: questions with fixed
 *   passages, so retrieval is not a variable). Writes one line per case, in the layout
 *   `tools/model-eval/score.pl` reads: answer, time to first word, whole time, tokens in
 *   and out, speeds, memory. Resumes if stopped.
 * - [retrievalTimings]: how long the app's own search takes per question.
 * - [runtimeProbes]: what the runtime does at its limits and what it offers that the app
 *   does not use yet: a prompt longer than the context, a fixed instruction processed
 *   once and reused, and schema-constrained output. `-e probe prefix|overflow|json`.
 *
 * Output: "Baseline" log lines and files in the app's media folder.
 */
@RunWith(AndroidJUnit4::class)
class BaselineEvalTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun media(name: String) = File(ctx.externalMediaDirs.first(), name)
    private fun r(x: Double, places: Int = 1): Double { var m = 1.0; repeat(places) { m *= 10 }; return Math.round(x * m) / m }

    private fun cases(): List<JSONObject> {
        val a = JSONObject(media(SET).readText()).getJSONArray("cases")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** The user message, laid out exactly as [Grounding.userPrompt] lays it out (the stored text is already cleaned). */
    private fun promptFor(c: JSONObject): String = buildString {
        appendLine("SOURCES (reference data only):")
        val ps = c.getJSONArray("passages")
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            appendLine()
            appendLine("[${p.getInt("index")}] ${p.getString("label")}")
            appendLine(p.getString("text"))
        }
        appendLine()
        appendLine("END OF SOURCES")
        appendLine()
        append("QUESTION: ").append(c.getString("question").trim())
    }

    @Test
    fun answersOnTheFixedSet() = runBlocking<Unit> {
        assumeTrue("needs $SET in the app's media folder: skipped", media(SET).exists())
        val ai = LocalAi.get(ctx)
        ai.refresh()
        val model = ai.obtain()
        assumeTrue("needs an on-device model: skipped", model != null)
        model!!
        val tag = args.getString("tag") ?: "run"
        val only = args.getString("only")
        val out = media("baseline-$tag.jsonl")
        val done = if (out.exists()) out.readLines().mapNotNull { runCatching { JSONObject(it).getString("id") }.getOrNull() }.toSet() else emptySet()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val file = ai.currentModelFile()
        media("baseline-$tag.meta").writeText(
            "model=${model.label}\nmodel_file=${file?.name}\nsize_bytes=${file?.length()}\ncontext=${LiteRtLmModel.MAX_CONTEXT_TOKENS}\n" +
                "max_answer_tokens=${AskSession.MAX_ANSWER_TOKENS}\ndevice_total_mb=${mi.totalMem shr 20}\ndevice_avail_mb_before=${mi.availMem shr 20}\n" +
                "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.SUPPORTED_ABIS.firstOrNull()}), ${Runtime.getRuntime().availableProcessors()} cores\n",
        )
        Log.i(TAG, "model ${model.label} total=${mi.totalMem shr 20} MB avail=${mi.availMem shr 20} MB")
        for (c in cases()) {
            val id = c.getString("id")
            if (id in done || (only != null && !id.startsWith(only))) continue
            val prompt = promptFor(c)
            val sb = StringBuilder()
            var err: String? = null
            val t0 = System.nanoTime()
            try {
                model.generate(Grounding.SYSTEM_INSTRUCTION, prompt, AskSession.MAX_ANSWER_TOKENS).collect { sb.append(it) }
            } catch (t: Throwable) {
                err = "${t.javaClass.simpleName}: ${t.message?.take(300)}"
            }
            val wall = (System.nanoTime() - t0) / 1e9
            val st = model.lastStats()
            am.getMemoryInfo(mi)
            val o = JSONObject().put("id", id).put("cat", c.getString("cat")).put("answer", sb.toString().trim())
                .put("wall_s", r(wall)).put("prompt_chars", prompt.length + Grounding.SYSTEM_INSTRUCTION.length)
                .put("avail_mb", mi.availMem shr 20)
            if (st != null) {
                o.put("prompt_ms", Math.round(st.timeToFirstChunkSeconds * 1000))
                st.prefillTokens?.let { o.put("prompt_tokens", it) }
                st.decodeTokens?.let { o.put("completion_tokens", it) }
                st.prefillTokensPerSecond?.let { o.put("prefill_tps", r(it)) }
                st.decodeTokensPerSecond?.let { o.put("tok_s", r(it)) }
                st.processRssMb?.let { o.put("rss_mb", it) }
                o.put("finish", if ((st.decodeTokens ?: 0) >= AskSession.MAX_ANSWER_TOKENS) "length" else "stop")
            }
            err?.let { o.put("error", it) }
            out.appendText(o.toString() + "\n")
            Log.i(TAG, "$id ${c.getString("cat")} ${r(wall)}s first=${st?.timeToFirstChunkSeconds?.let { r(it) }}s in=${st?.prefillTokens} out=${st?.decodeTokens} ${err ?: ""}")
        }
        Log.i(TAG, "DONE ${out.absolutePath}")
    }

    @Test
    fun retrievalTimings() = runBlocking<Unit> {
        assumeTrue("needs $SET in the app's media folder: skipped", media(SET).exists())
        val zim = ZimLibrary.get(ctx)
        val tOpen = System.nanoTime()
        zim.restore()
        val openMs = (System.nanoTime() - tOpen) / 1e6
        val retriever = Retriever(LibraryStore.get(ctx), zim)
        val out = media("baseline-retrieval.jsonl")
        out.delete()
        out.appendText(JSONObject().put("id", "open").put("first_ms", r(openMs)).put("references", zim.currentInfos().size).toString() + "\n")
        for (c in cases()) {
            val id = c.getString("id")
            if (!id.startsWith("r")) continue
            val q = c.getString("question")
            val t0 = System.nanoTime()
            val ps = retriever.retrieve(q)
            val first = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            retriever.retrieve(q)
            val again = (System.nanoTime() - t1) / 1e6
            out.appendText(JSONObject().put("id", id).put("first_ms", r(first)).put("repeat_ms", r(again)).put("passages", ps.size).toString() + "\n")
            Log.i(TAG, "retrieval $id first=${r(first)} ms repeat=${r(again)} ms passages=${ps.size}")
        }
        Log.i(TAG, "DONE ${out.absolutePath}")
    }

    @OptIn(ExperimentalApi::class)
    @Test
    fun runtimeProbes() = runBlocking<Unit> {
        assumeTrue("needs $SET in the app's media folder: skipped", media(SET).exists())
        val file = LocalAi.get(ctx).currentModelFile()
        assumeTrue("needs an on-device model: skipped", file != null)
        file!!
        val probe = args.getString("probe") ?: "prefix"
        ExperimentalFlags.enableBenchmark = true
        if (probe == "json") ExperimentalFlags.enableConversationConstrainedDecoding = true
        val cache = File(ctx.cacheDir, "litertlm").apply { mkdirs() }

        runCatching {
            ModelInfo.from(file.absolutePath).use { info ->
                Log.i(TAG, "PROBE modelinfo file=${file.name} type=${info.modelType} maxContextTokens=${info.maxContextTokens()} dynamicContext=${info.isDynamicContext()} minRuntime=${info.minRuntimeVersion()}")
                (info as? ModelInfo.Llm)?.let { Log.i(TAG, "PROBE modelinfo thinking=${it.supportsThinking()} functionCalling=${it.supportsFunctionCalling()} defaultSampler=${it.defaultSamplerParams()}") }
            }
        }.onFailure { Log.i(TAG, "PROBE modelinfo failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}") }

        val tLoad = System.nanoTime()
        val engine = Engine(
            EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = LiteRtLmModel.MAX_CONTEXT_TOKENS, cacheDir = cache.absolutePath),
        )
        engine.initialize()
        Log.i(TAG, "PROBE engine loaded in ${r((System.nanoTime() - tLoad) / 1e9)} s (CPU, context ${LiteRtLmModel.MAX_CONTEXT_TOKENS})")
        val sampler = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2)
        val byId = cases().associateBy { it.getString("id") }
        val sys = Contents.of(Grounding.SYSTEM_INSTRUCTION)
        try {
            when (probe) {
                // Is the fixed instruction processed again for every question, and can that be avoided?
                "prefix" -> {
                    val ids = listOf("r08", "r11", "r16", "r22")
                    for (id in ids) runCatching {
                        val c = engine.createConversation(ConversationConfig(systemInstruction = sys, samplerConfig = sampler, maxOutputToken = 16))
                        val t0 = System.nanoTime()
                        c.sendMessage(promptFor(byId.getValue(id)))
                        val wall = (System.nanoTime() - t0) / 1e9
                        val b = c.getBenchmarkInfo()
                        Log.i(TAG, "PROBE prefix fresh $id prefillTokens=${b.lastPrefillTokenCount} prefillTps=${r(b.lastPrefillTokensPerSecond)} firstToken=${r(b.timeToFirstTokenInSecond, 2)}s wall=${r(wall)}s conversationTokens=${c.getTokenCount()}")
                        c.close()
                    }.onFailure { Log.i(TAG, "PROBE prefix fresh $id failed: ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
                    runCatching {
                        val tBase = System.nanoTime()
                        val base = engine.createConversation(
                            ConversationConfig(systemInstruction = sys, samplerConfig = sampler, maxOutputToken = 16, prefillPrefaceOnInit = true),
                        )
                        Log.i(TAG, "PROBE prefix base built in ${r((System.nanoTime() - tBase) / 1e9, 2)} s tokens=${base.getTokenCount()}")
                        for (id in ids) runCatching {
                            val tClone = System.nanoTime()
                            val c = base.clone()
                            val cloneS = (System.nanoTime() - tClone) / 1e9
                            val t0 = System.nanoTime()
                            c.sendMessage(promptFor(byId.getValue(id)))
                            val wall = (System.nanoTime() - t0) / 1e9
                            val b = c.getBenchmarkInfo()
                            Log.i(TAG, "PROBE prefix reused $id clone=${r(cloneS, 2)}s prefillTokens=${b.lastPrefillTokenCount} prefillTps=${r(b.lastPrefillTokensPerSecond)} firstToken=${r(b.timeToFirstTokenInSecond, 2)}s wall=${r(wall)}s conversationTokens=${c.getTokenCount()}")
                            c.close()
                        }.onFailure { Log.i(TAG, "PROBE prefix reused $id failed: ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
                        base.close()
                    }.onFailure { Log.i(TAG, "PROBE prefix base failed: ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
                }
                // What happens when the prompt, or prompt plus answer, does not fit the context?
                "overflow" -> {
                    val one = promptFor(byId.getValue("r11"))
                    val block = one.substringAfter("SOURCES (reference data only):").substringBefore("END OF SOURCES")
                    fun sized(chars: Int) = buildString {
                        append("SOURCES (reference data only):")
                        while (length < chars) append(block)
                        append("END OF SOURCES\n\nQUESTION: how do I treat a burn")
                    }
                    for ((name, chars, maxOut) in listOf(Triple("fits", 3000, 64), Triple("answer-does-not-fit", 6600, 384), Triple("prompt-does-not-fit", 12000, 64))) runCatching {
                        val c = engine.createConversation(ConversationConfig(systemInstruction = sys, samplerConfig = sampler, maxOutputToken = maxOut))
                        val t0 = System.nanoTime()
                        val msg = c.sendMessage(sized(chars)).toString()
                        val wall = (System.nanoTime() - t0) / 1e9
                        val b = c.getBenchmarkInfo()
                        Log.i(TAG, "PROBE overflow $name chars=$chars maxOut=$maxOut prefillTokens=${b.lastPrefillTokenCount} decodeTokens=${b.lastDecodeTokenCount} conversationTokens=${c.getTokenCount()} wall=${r(wall)}s answerChars=${msg.length} answer=${msg.replace('\n', ' ').take(160)}")
                        c.close()
                    }.onFailure { Log.i(TAG, "PROBE overflow $name chars=$chars failed: ${it.javaClass.simpleName}: ${it.message?.take(400)}") }
                }
                // Can the runtime force the reply to match a JSON schema (needed for a question-plan step)?
                "json" -> {
                    val questions = listOf(
                        "can I weld with a car battery",
                        "my truck just clicks when I turn the key",
                        "guy got bit by a rattler what now",
                    )
                    for (q in questions) runCatching {
                        val c = engine.createConversation(
                            ConversationConfig(systemInstruction = Contents.of(PLAN_INSTRUCTION), samplerConfig = sampler, maxOutputToken = 160, enableResponseFormat = true),
                        )
                        val t0 = System.nanoTime()
                        val msg = c.sendMessage("QUESTION: $q", responseFormat = ResponseFormat.json(PLAN_SCHEMA)).toString()
                        val wall = (System.nanoTime() - t0) / 1e9
                        val b = c.getBenchmarkInfo()
                        val valid = runCatching { JSONObject(msg).has("keyword_query") }.getOrDefault(false)
                        Log.i(TAG, "PROBE json validJson=$valid wall=${r(wall)}s prefillTokens=${b.lastPrefillTokenCount} decodeTokens=${b.lastDecodeTokenCount} decodeTps=${r(b.lastDecodeTokensPerSecond)} q=$q")
                        Log.i(TAG, "PROBE json out=${msg.replace('\n', ' ').take(600)}")
                        c.close()
                    }.onFailure { Log.i(TAG, "PROBE json failed: ${it.javaClass.simpleName}: ${it.message?.take(400)} q=$q") }
                }
            }
        } finally {
            runCatching { engine.close() }
        }
        Log.i(TAG, "PROBE done $probe")
    }

    private companion object {
        const val TAG = "Baseline"
        const val SET = "evalset-fixed.json"

        const val PLAN_INSTRUCTION =
            "You prepare a question for a search of an offline reference library. Restate the question as one clear, complete " +
                "question. Name its intent. Give search key words for what the question is really about, using the names a " +
                "reference book would use instead of slang. Say in a few words the look-alike subject it is NOT about. Reply with JSON only."

        const val PLAN_SCHEMA = """{"type":"object","properties":{"standalone_question":{"type":"string"},"intent":{"type":"string","enum":["how_to","can_i","is_it_safe","what_is","diagnose","treat","dose","identify","procedure","other"]},"keyword_query":{"type":"string"},"not_about":{"type":"string"}},"required":["standalone_question","intent","keyword_query","not_about"]}"""
    }
}
