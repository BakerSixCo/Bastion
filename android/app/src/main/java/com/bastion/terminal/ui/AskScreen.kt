package com.bastion.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.ai.AiState
import com.bastion.terminal.ai.GenerationStats
import com.bastion.terminal.ai.Grounding
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.ai.LocalModel
import com.bastion.terminal.ai.Passage
import com.bastion.terminal.data.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

private const val MAX_ANSWER_TOKENS = 384

@Composable
fun AskScreen(modifier: Modifier = Modifier, onOpen: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    val ai = remember { LocalAi.get(ctx) }
    val aiState by ai.state.collectAsState()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var passages by remember { mutableStateOf<List<Passage>>(emptyList()) }
    var askedFor by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var answerError by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<GenerationStats?>(null) }
    var modelLabel by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<Grounding.CitationCheck?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf<LocalModel?>(null) }
    // Explicit state: Job.isActive is not observable by Compose.
    var generating by remember { mutableStateOf(false) }

    fun resetAnswer() {
        answer = ""; answerError = null; stats = null; check = null
    }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Ask", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                Text(
                    aiIntro(aiState),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Question or keywords") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                enabled = query.isNotBlank() && !generating,
                onClick = {
                    scope.launch {
                        resetAnswer()
                        askedFor = query
                        passages = store.retrieve(query)
                        searched = true
                    }
                },
            ) { Text("Search library") }
        }

        if (searched && passages.isEmpty()) {
            item {
                Text(
                    "No matching passages in the library — not enough local evidence to answer.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (passages.isNotEmpty()) {
            item {
                Text(
                    "Sources (${passages.size}) — these exact passages are what the local AI may use:",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(passages) { p ->
                SourceCard(p, cited = check?.cited?.contains(p.index) == true) { onOpen(p.docId, askedFor) }
            }
            item {
                AnswerControls(
                    aiState = aiState,
                    generating = generating,
                    onAnswer = {
                        resetAnswer()
                        generating = true
                        job = scope.launch {
                            val model = ai.obtain()
                            if (model == null) {
                                answerError = (ai.state.value as? AiState.Failed)?.reason
                                    ?: "Local AI is not available."
                                generating = false
                                return@launch
                            }
                            running = model
                            modelLabel = model.label
                            try {
                                model.generate(
                                    Grounding.SYSTEM_INSTRUCTION,
                                    Grounding.userPrompt(askedFor, passages),
                                    MAX_ANSWER_TOKENS,
                                ).collect { answer += it }
                                stats = model.lastStats()
                                check = Grounding.checkCitations(answer, passages.size)
                                // One line per answer for benchmarking (no document text logged).
                                android.util.Log.i(
                                    "BastionAI",
                                    "done ${model.label} | ${stats?.let(::statsLine)} | cited=${check?.cited} invalid=${check?.invalid} noAnswer=${check?.isNoAnswer}",
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
                    },
                    onStop = {
                        running?.cancel()
                        job?.cancel()
                    },
                )
            }
        }

        if (answer.isNotEmpty() || answerError != null) {
            item { AnswerCard(answer, answerError, modelLabel, stats, check, generating) }
        }
    }
}

private fun aiIntro(s: AiState): String = when (s) {
    is AiState.NoModel ->
        "Local AI is not installed. Ask searches your library and shows real matching passages — " +
            "retrieval only, no generated answers. Import a model on the Home tab to enable answers."
    is AiState.Importing -> "A model is being imported (see Home). Retrieval works meanwhile."
    is AiState.Ready, is AiState.Loaded, is AiState.Loading ->
        "Search first: Ask shows the matching passages. Then the on-device model can answer " +
            "using only those passages, with [n] citations you can open. Nothing leaves this phone."
    is AiState.Failed -> "Local AI failed: ${s.reason}. Retrieval still works."
}

@Composable
private fun SourceCard(p: Passage, cited: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (cited) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "[${p.index}] ${p.label}" + if (cited) "  · cited" else "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(annotateSnippet(p.markedText, HighlightColor), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnswerControls(aiState: AiState, generating: Boolean, onAnswer: () -> Unit, onStop: () -> Unit) {
    when (aiState) {
        is AiState.NoModel, is AiState.Importing -> Text(
            "No local model installed — answers unavailable. The passages above are the evidence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !generating, onClick = onAnswer) { Text("Answer from these sources") }
                if (generating) OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            if (aiState is AiState.Loading) {
                Text("Loading model into memory — the first load can take a while…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (generating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AnswerCard(
    answer: String,
    error: String?,
    label: String,
    stats: GenerationStats?,
    check: Grounding.CitationCheck?,
    generating: Boolean,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local AI answer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (answer.isNotEmpty()) Text(answer, style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!generating && check != null) {
                when {
                    check.isNoAnswer -> Text(
                        "The model reports the sources don't answer this.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    check.cited.isEmpty() -> Text(
                        "This answer cites no source — treat it as unverified.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        "Cites sources ${check.cited.sorted().joinToString { "[$it]" }}. Open them to verify.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (check.invalid.isNotEmpty()) Text(
                    "Warning: cites nonexistent source(s) ${check.invalid.sorted().joinToString { "[$it]" }}.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (label.isNotEmpty()) Text(
                "Generated on this device by $label. Not fact-checked — the sources are the record.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stats?.let {
                Text(statsLine(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun statsLine(s: GenerationStats): String {
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
