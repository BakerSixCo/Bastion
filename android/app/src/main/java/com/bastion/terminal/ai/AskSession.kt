package com.bastion.terminal.ai

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The Ask screen's state and work, scoped to the app process instead of the
 * screen: switching tabs or opening a cited source no longer discards the
 * answer or cancels a running generation. State fields are Compose state so
 * the screen recomposes from them.
 */
class AskSession private constructor(context: Context) {
    private val ai = LocalAi.get(context)
    private val retriever = Retriever(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var query by mutableStateOf("")
    var askedFor by mutableStateOf("")
        private set
    var passages by mutableStateOf<List<Passage>>(emptyList())
        private set
    var searched by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    var answer by mutableStateOf("")
        private set
    var answerError by mutableStateOf<String?>(null)
        private set
    var stats by mutableStateOf<GenerationStats?>(null)
        private set
    var modelLabel by mutableStateOf("")
        private set
    var check by mutableStateOf<Grounding.CitationCheck?>(null)
        private set
    var generating by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var running: LocalModel? = null

    private fun resetAnswer() {
        answer = ""; answerError = null; stats = null; check = null
    }

    fun search() {
        if (query.isBlank() || generating) return
        val q = query
        scope.launch {
            searching = true
            resetAnswer()
            askedFor = q
            passages = retriever.retrieve(q)
            searched = true
            searching = false
            Log.i(TAG, "retrieved ${passages.size} | zimArticles=${retriever.lastZimArticles} | ${passages.map { it.label }}")
            Log.i(TAG, "choice ${retriever.lastChoice}")
        }
    }

    fun answer() {
        if (generating || passages.isEmpty()) return
        resetAnswer()
        generating = true
        val sources = passages
        val question = askedFor
        job = scope.launch {
            val model = ai.obtain()
            if (model == null) {
                answerError = (ai.state.value as? AiState.Failed)?.reason ?: "Local AI is not available."
                generating = false
                return@launch
            }
            running = model
            modelLabel = model.label
            try {
                model.generate(Grounding.SYSTEM_INSTRUCTION, Grounding.userPrompt(question, sources), MAX_ANSWER_TOKENS)
                    .collect { answer += it }
                stats = model.lastStats()
                check = Grounding.checkCitations(answer, sources.size)
                // One line per answer for benchmarking (no document text logged).
                Log.i(
                    TAG,
                    "done ${model.label} | ${stats?.let(::statsLine)} | cited=${check?.cited} " +
                        "invalid=${check?.invalid} noAnswer=${check?.isNoAnswer}",
                )
            } catch (c: CancellationException) {
                answerError = "Stopped."
                throw c
            } catch (t: Throwable) {
                answerError = "Generation failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                running = null
                generating = false
            }
        }
    }

    fun stop() {
        running?.cancel()
        job?.cancel()
    }

    companion object {
        const val TAG = "BastionAI"
        private const val MAX_ANSWER_TOKENS = 384

        @Volatile private var inst: AskSession? = null

        fun get(context: Context): AskSession =
            inst ?: synchronized(this) { inst ?: AskSession(context.applicationContext).also { inst = it } }

        fun statsLine(s: GenerationStats): String {
            fun f(d: Double) = String.format(Locale.US, "%.1f", d)
            return buildList {
                s.loadSeconds?.let { add("load ${f(it)} s") }
                add("first text ${f(s.timeToFirstChunkSeconds)} s")
                add("total ${f(s.totalSeconds)} s")
                if (s.prefillTokens != null && s.prefillTokensPerSecond != null) add("prefill ${s.prefillTokens} tok @ ${f(s.prefillTokensPerSecond)}/s")
                if (s.decodeTokens != null && s.decodeTokensPerSecond != null) add("decode ${s.decodeTokens} tok @ ${f(s.decodeTokensPerSecond)}/s")
                s.processRssMb?.let { add("app RAM $it MB") }
            }.joinToString(" · ")
        }
    }
}
