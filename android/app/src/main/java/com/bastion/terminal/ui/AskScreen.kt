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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.ai.AiState
import com.bastion.terminal.ai.AskSession
import com.bastion.terminal.ai.Card as VerifiedCard
import com.bastion.terminal.ai.CheckedAnswer
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.kiwix.ZimState
import com.bastion.terminal.ai.GenerationStats
import com.bastion.terminal.ai.Grounding
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.ai.LocalModel
import com.bastion.terminal.ai.Passage
import com.bastion.terminal.ai.SourceTier
import com.bastion.terminal.mesh.MeshBridge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue


@Composable
fun AskScreen(
    modifier: Modifier = Modifier,
    onOpen: (Long, String) -> Unit,
    onOpenZim: (zimFile: String, path: String, query: String) -> Unit,
) {
    val ctx = LocalContext.current
    val ai = remember { LocalAi.get(ctx) }
    val aiState by ai.state.collectAsState()
    val zimState by remember { ZimLibrary.get(ctx) }.state.collectAsState()
    // App-scoped: survives tab switches and opening a source.
    val s = remember { AskSession.get(ctx) }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Ask", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                Text(
                    aiIntro(aiState) + "\n" + zimIntro(zimState),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = s.query,
                onValueChange = { s.query = it },
                label = { Text("Question or keywords") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(
                enabled = s.query.isNotBlank() && !s.generating && !s.searching,
                onClick = { s.search() },
            ) { Text(if (zimState is ZimState.Ready) "Search library + reference" else "Search library") }
        }
        if (s.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        if (s.searched && !s.searching && s.passages.isEmpty()) {
            item {
                Text(
                    "No matching passages — not enough local evidence to answer.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (s.passages.isNotEmpty()) {
            item {
                Text(
                    "Sources (${s.passages.size}) — these exact passages are what the local AI may use:",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(s.passages) { p ->
                SourceCard(p, cited = s.check?.cited?.contains(p.index) == true) {
                    val path = p.zimPath
                    // Highlight the key terms (not "the", "of" … from the raw question).
                    val hl = Grounding.keyTerms(s.askedFor).joinToString(" ")
                    val file = p.zimFile
                    if (path != null && file != null) onOpenZim(file, path, hl) else onOpen(p.docId, hl)
                }
            }
            item {
                AnswerControls(
                    aiState = aiState,
                    generating = s.generating,
                    onAnswer = { s.answer() },
                    onStop = { s.stop() },
                )
            }
        }

        if (s.answer.isNotEmpty() || s.answerError != null) {
            item { AnswerCard(s.answer, s.answerError, s.modelLabel, s.stats, s.check, s.generating, s.withheld, s.stage, s.card) }
        }
    }
}

private fun zimIntro(z: ZimState): String = when (z) {
    is ZimState.Ready -> {
        // Sources of truth (docs/library-plan.md): say plainly which references the AI may use.
        val (used, unused) = z.zims.partition { SourceTier.forZim(it.file.ifEmpty { it.fileName }).usedForAnswers }
        "Offline reference searched too: " +
            used.joinToString { "“${it.title}” (${SourceTier.forZim(it.file.ifEmpty { it.fileName }).label})" } + "." +
            (if (unused.isEmpty()) "" else " Not used for answers: " +
                unused.joinToString { "“${it.title}” (${SourceTier.forZim(it.file.ifEmpty { it.fileName }).label.substringBefore(" —")})" } + ".")
    }
    is ZimState.Opening -> "Opening the offline reference…"
    is ZimState.Copying -> "Copying the offline reference into BASTION (see Home)…"
    is ZimState.Failed -> "Offline reference unavailable: ${z.reason}"
    is ZimState.NoZim -> "No offline reference (ZIM) added — add one on Home to search Wikipedia-style content."
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
    withheld: CheckedAnswer.Decision?,
    stage: String,
    card: VerifiedCard?,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local AI answer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (generating) Text(
                (stage.ifEmpty { "Starting…" }) + " Answers are checked before they are shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card != null) Text(
                "Verified answer · " + card.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (answer.isNotEmpty()) Text(answer, style = MaterialTheme.typography.bodyMedium)
            card?.let { c ->
                Text("Source: " + c.sources.joinToString("; "), style = MaterialTheme.typography.bodySmall)
                c.quotes.forEach { q ->
                    Text("“" + q.text + "”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Prepared from official sources, quotes checked word for word, approved by the owner. Not written by the model.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            withheld?.let { Text(withheldReason(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!generating && check != null && card == null) {
                when {
                    withheld != null -> Unit
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
                if (check.tables.isNotEmpty()) Text(
                    Grounding.TABLE_NOTE + " Table source: " + check.tables.sorted().joinToString { "[$it]" } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                Text(AskSession.statsLine(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Hand a finished, grounded answer to the mesh (the user edits and sends it in Meshtastic).
            val ctx = LocalContext.current
            var meshOpen by rememberSaveable { mutableStateOf(false) }
            if (!generating && answer.isNotBlank() && check?.isNoAnswer != true && remember { MeshBridge.isInstalled(ctx) }) {
                OutlinedButton(onClick = { meshOpen = true }) { Text("Send to mesh…") }
            }
            if (meshOpen) MeshComposeDialog(initial = answer, onDismiss = { meshOpen = false })
        }
    }
}


/** One plain line on why a written answer was not shown. */
private fun withheldReason(d: CheckedAnswer.Decision): String = when (d.failed) {
    CheckedAnswer.Failed.SUBJECT -> "BASTION withheld the model's answer: the sources it used do not mention " +
        d.missing.joinToString(", ") { "“$it”" } + ", so they are about something else."
    CheckedAnswer.Failed.NUMBER -> "BASTION withheld the model's answer: it gave a number (" +
        d.missing.joinToString(", ") + ") that is not in the sources."
    CheckedAnswer.Failed.OFF_TARGET -> "BASTION withheld the model's answer: on checking, it answers a different question than the one asked."
    else -> ""
}
