package com.bastion.terminal.ui

import android.content.ActivityNotFoundException
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import com.bastion.terminal.ai.ModelBackend
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.ai.AiState
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.kiwix.ZimState
import com.bastion.terminal.data.LibraryStore
import kotlinx.coroutines.launch
import com.bastion.terminal.data.TextUtil

private data class CompanionApp(val label: String, val pkg: String, val role: String)

private val COMPANIONS = listOf(
    CompanionApp("Kiwix", "org.kiwix.kiwixmobile", "Offline ZIM library (Wikipedia and more)"),
    CompanionApp("Meshtastic", "com.geeksville.mesh", "LoRa mesh messaging — needs external radio hardware"),
    CompanionApp("ATAK-CIV", "com.atakmap.app.civ", "Tactical mapping"),
    CompanionApp("Organic Maps", "app.organicmaps", "Offline navigation maps"),
)

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    val tick by store.changeCounter.collectAsState()
    var docCount by remember { mutableIntStateOf(0) }
    var docBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tick) {
        val (c, b) = store.stats()
        docCount = c
        docBytes = b
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BASTION", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Off-grid field terminal — v0.1 (library/search milestone)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            title = "Library",
            ok = docCount > 0,
            body = if (docCount > 0) {
                "$docCount document(s), ${TextUtil.formatSize(docBytes)} indexed. Full-text search works fully offline."
            } else {
                "No documents yet. Import plain-text or Markdown files in the Library tab."
            },
        )

        LocalAiCard()

        ZimCard()

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Companion apps", style = MaterialTheme.typography.titleMedium)
                COMPANIONS.forEach { comp ->
                    val launch = remember(comp.pkg) { ctx.packageManager.getLaunchIntentForPackage(comp.pkg) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(comp.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                comp.role,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (launch != null) {
                            TextButton(onClick = { ctx.startActivity(launch) }) { Text("Open") }
                        } else {
                            Text(
                                "not installed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "“Open” only launches the app. No live data integration exists yet; " +
                        "when one does, its exact capabilities will be documented here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val freeBytes = remember { StatFs(ctx.filesDir.path).availableBytes }
        StatusCard(
            title = "Storage",
            ok = freeBytes > 500_000_000L,
            body = "${TextUtil.formatSize(freeBytes)} free in app storage.",
        )
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, body: String) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                (if (ok) "● " else "○ ") + title,
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LocalAiCard() {
    val ctx = LocalContext.current
    val ai = remember { LocalAi.get(ctx) }
    val state by ai.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = ai.importModel(uri) }
    }
    val busy = state is AiState.Importing || state is AiState.Loading

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val ok = state is AiState.Ready || state is AiState.Loaded
            Text(
                (if (ok) "● " else "○ ") + "Local AI",
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(localAiBody(state), style = MaterialTheme.typography.bodyMedium)
            (state as? AiState.Importing)?.let { s ->
                val total = s.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { s.copiedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (state is AiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        message = null
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (_: ActivityNotFoundException) {
                            message = "This device has no file picker app, so a model cannot be imported here."
                        }
                    },
                ) { Text(if (state is AiState.NoModel) "Import model file" else "Replace model") }
                if (state is AiState.Loaded) {
                    OutlinedButton(onClick = { scope.launch { ai.unload() } }) { Text("Unload") }
                }
            }
            if (state !is AiState.NoModel) {
                var backend by remember { mutableStateOf(ai.backend()) }
                Text("Compute: " + when (backend) {
                    ModelBackend.AUTO -> "Auto (GPU if available, else CPU)"
                    ModelBackend.GPU -> "GPU only"
                    ModelBackend.CPU -> "CPU only"
                }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelBackend.entries.forEach { b ->
                        FilterChip(
                            selected = backend == b,
                            enabled = !busy,
                            onClick = { backend = b; scope.launch { ai.setBackend(b) } },
                            label = { Text(if (b == ModelBackend.AUTO) "Auto" else b.name) },
                        )
                    }
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Nothing is sent to any cloud service: this app does not declare the Internet permission at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun localAiBody(s: AiState): String = when (s) {
    is AiState.NoModel -> "Not installed. No model file is present, so Ask returns matching library " +
        "passages only. Import a LiteRT-LM model (.litertlm), e.g. Gemma 4 E2B, copied onto this phone."
    is AiState.Importing -> "Importing model… ${TextUtil.formatSize(s.copiedBytes)}" +
        (s.totalBytes?.let { " of ${TextUtil.formatSize(it)}" } ?: "")
    is AiState.Ready -> "Model file present: ${s.file.name} (${TextUtil.formatSize(s.file.length())}). " +
        "Not loaded yet — it loads on the first question in Ask."
    is AiState.Loading -> "Loading ${s.file.name} into memory…"
    is AiState.Loaded -> "Loaded: ${s.label} (load took ${"%.1f".format(java.util.Locale.US, s.loadSeconds)} s). " +
        "Ask can answer from your library with citations." + (s.note?.let { "\n$it" } ?: "")
    is AiState.Failed -> s.reason + (s.file?.let { " File: ${it.name}." } ?: "")
}

@Composable
private fun ZimCard() {
    val ctx = LocalContext.current
    val zim = remember { ZimLibrary.get(ctx) }
    val state by zim.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var toSd by remember { mutableStateOf(false) }
    val sd = remember(state) { zim.sdFolder() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { message = zim.add(uri, toSdCard = toSd) }
    }
    val busy = state is ZimState.Opening || state is ZimState.Copying
    fun pick(sdCard: Boolean) {
        message = null
        toSd = sdCard
        try {
            picker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            message = "This device has no file picker app, so a ZIM cannot be added here."
        }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val ok = state is ZimState.Ready
            Text(
                (if (ok) "● " else "○ ") + "Offline reference (ZIM)",
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            when (val s = state) {
                is ZimState.Ready -> {
                    Text(
                        "Ask searches ${if (s.zims.size == 1) "this file" else "all ${s.zims.size} files"} alongside your library:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    s.zims.forEach { z ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "“${z.title}”" + (z.date?.let { " ($it)" } ?: "") +
                                    " — ${"%,d".format(z.articleCount)} articles, ${TextUtil.formatSize(z.sizeBytes)}" +
                                    (if (z.onSdCard) " · SD card" else " · phone storage") + "\n${z.fileName}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(enabled = !busy, onClick = { scope.launch { message = zim.remove(z.file) } }) {
                                Text("Remove")
                            }
                        }
                    }
                    s.problems.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> Text(
                    when (s) {
                        is ZimState.NoZim -> "None added. Add a Kiwix .zim file (e.g. offline Wikipedia) and Ask " +
                            "will search it alongside your library, citing the articles it uses. Several can be added. " +
                            "Large files can instead be copied over USB into ${zim.folder().absolutePath}" +
                            (sd?.let { " or, on the SD card, ${it.dir.absolutePath}" } ?: "") + ", then Rescan."
                        is ZimState.Copying -> "Copying into BASTION's reference folder... ${TextUtil.formatSize(s.copiedBytes)}" +
                            (s.totalBytes?.let { " of ${TextUtil.formatSize(it)}" } ?: "")
                        is ZimState.Opening -> "Opening…"
                        is ZimState.Failed -> s.reason
                        is ZimState.Ready -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            (state as? ZimState.Copying)?.let { c ->
                val total = c.totalBytes
                if (total != null && total > 0) LinearProgressIndicator(progress = { c.copiedBytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state is ZimState.Opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { pick(false) }) { Text("Add ZIM") }
                if (sd != null) OutlinedButton(enabled = !busy, onClick = { pick(true) }) { Text("Add to SD card") }
                TextButton(enabled = !busy, onClick = {
                    message = null
                    scope.launch { zim.restore(); message = "Rescanned the reference folders." }
                }) { Text("Rescan") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
