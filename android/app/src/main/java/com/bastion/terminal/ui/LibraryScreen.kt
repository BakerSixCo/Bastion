package com.bastion.terminal.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.data.DocMeta
import com.bastion.terminal.data.ImportResult
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.data.Pack
import com.bastion.terminal.data.PackKind
import com.bastion.terminal.data.SearchHit
import com.bastion.terminal.data.TextUtil
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the plan editor is working on. [docId] < 0 = a new plan. */
private data class Editing(val docId: Long, val packId: Long?, val title: String, val text: String)

/**
 * Library, organised into **packs**: a trip, a hunting plan, a bug-out plan,
 * a contact list. Each pack has two switches — whether Ask uses it, and
 * whether questions arriving over the mesh may be answered from it.
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier, onOpen: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    val tick by store.changeCounter.collectAsState()
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<DocMeta>>(emptyList()) }
    var packs by remember { mutableStateOf<List<Pack>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var newPack by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var deletePack by remember { mutableStateOf<Pack?>(null) }
    // Which pack the next picked file goes into (-1 = general library).
    var importTarget by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(tick) {
        docs = store.listDocs()
        packs = store.listPacks()
    }
    LaunchedEffect(query, tick) {
        hits = if (query.isBlank()) emptyList() else store.search(query)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = when (val r = store.importFromUri(uri, importTarget.takeIf { it >= 0 })) {
                    is ImportResult.Ok -> "Imported “${r.doc.title}” (${TextUtil.formatSize(r.doc.sizeBytes)})."
                    is ImportResult.Rejected -> r.reason
                }
                busy = false
            }
        }
    }
    fun pick(packId: Long?) {
        message = null
        importTarget = packId ?: -1L
        try {
            picker.launch(arrayOf("text/*", "application/pdf", "application/epub+zip", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            message = "This device has no file picker app, so documents cannot be imported here."
        }
    }

    editing?.let { e ->
        PlanEditor(
            e,
            onCancel = { editing = null },
            onSave = { title, text ->
                scope.launch {
                    message = when (val r = store.saveNote(title, text, e.packId, e.docId.takeIf { it >= 0 })) {
                        is ImportResult.Ok -> "Saved “${r.doc.title}”."
                        is ImportResult.Rejected ->
                            if (r.reason.startsWith("Already in the library")) "No changes to save." else r.reason
                    }
                    editing = null
                }
            },
        )
        return
    }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Library", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Keep each plan in its own pack. A pack can be a trip, a hunting plan, a bug-out plan or a " +
                    "contact list — write it here or import files into it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { newPack = true }, enabled = !busy) { Text("New pack") }
                OutlinedButton(onClick = { pick(null) }, enabled = !busy) { Text("Import document") }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        message?.let { m ->
            item { Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; message = null },
                label = { Text("Search the library") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (query.isNotBlank()) {
            items(hits) { h -> HitCard(h, query, onOpen) }
            if (hits.isEmpty()) item { Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }

        items(packs, key = { "p${it.id}" }) { p ->
            val kind = PackKind.of(p.kind)
            val mine = docs.filter { it.packId == p.id }
            PackCard(
                title = p.name,
                subtitle = "${kind.label} · ${mine.size} document(s)",
                inAsk = p.inAsk,
                meshOk = p.meshOk,
                onInAsk = { v -> scope.launch { store.setPack(p.id, inAsk = v) } },
                onMeshOk = { v -> scope.launch { store.setPack(p.id, meshOk = v) } },
                docs = mine,
                onOpen = { d -> onOpen(d.id, "") },
                onEdit = { d -> scope.launch { editing = Editing(d.id, d.packId, d.title, store.docText(d.id).orEmpty()) } },
                actions = {
                    OutlinedButton(onClick = { editing = Editing(-1, p.id, kind.planTitle, kind.starter()) }) { Text("Write plan") }
                    OutlinedButton(onClick = { pick(p.id) }) { Text("Import file") }
                    TextButton(onClick = { deletePack = p }) { Text("Delete") }
                },
            )
        }
        item(key = "general") {
            val mine = docs.filter { d -> d.packId == null || packs.none { it.id == d.packId } }
            PackCard(
                title = "General library",
                subtitle = "Documents not in a pack · ${mine.size} document(s)",
                inAsk = store.generalInAsk().also { tick },
                meshOk = store.generalMeshOk(),
                onInAsk = { v -> store.setGeneral(inAsk = v) },
                onMeshOk = { v -> store.setGeneral(meshOk = v) },
                docs = mine,
                onOpen = { d -> onOpen(d.id, "") },
                onEdit = { d -> scope.launch { editing = Editing(d.id, null, d.title, store.docText(d.id).orEmpty()) } },
                actions = {
                    OutlinedButton(onClick = { editing = Editing(-1, null, "Note", "") }) { Text("Write note") }
                },
            )
        }
    }

    if (newPack) NewPackDialog(
        onDismiss = { newPack = false },
        onCreate = { name, kind ->
            newPack = false
            scope.launch {
                val id = store.createPack(name, kind.name)
                message = "Created “$name”. Fill in the plan, or import files into it."
                editing = Editing(-1, id, kind.planTitle, kind.starter())
            }
        },
    )
    deletePack?.let { p ->
        AlertDialog(
            onDismissRequest = { deletePack = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("This deletes the pack and the ${p.docCount} document(s) in it from BASTION. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { deletePack = null; scope.launch { store.deletePack(p.id); message = "Deleted “${p.name}”." } }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackCard(
    title: String,
    subtitle: String,
    inAsk: Boolean,
    meshOk: Boolean,
    onInAsk: (Boolean) -> Unit,
    onMeshOk: (Boolean) -> Unit,
    docs: List<DocMeta>,
    onOpen: (DocMeta) -> Unit,
    onEdit: (DocMeta) -> Unit,
    actions: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Use in Ask", "The AI on this phone answers from it.", inAsk, onInAsk)
            SwitchRow(
                "Include in mesh retrieval",
                "Saved as a library preference. Automatic radio replies use approved cards only.",
                meshOk, onMeshOk,
            )
            docs.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpen(d) }) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${TextUtil.formatSize(d.sizeBytes)} · ${DATE.format(Date(d.importedAt))}" +
                                if (d.isNote) " · written here" else " · ${d.filename}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.isNote) TextButton(onClick = { onEdit(d) }) { Text("Edit") }
                }
            }
            if (docs.isEmpty()) Text("Nothing in here yet.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // Local state so the switch moves immediately; the store reloads behind it.
    var on by remember(checked) { mutableStateOf(checked) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
private fun NewPackDialog(onDismiss: () -> Unit, onCreate: (String, PackKind) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(PackKind.TRIP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New pack") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) },
                    label = { Text("Name (e.g. Elk camp 2027)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("What is it?", style = MaterialTheme.typography.labelLarge)
                PackKind.entries.forEach { k ->
                    Card(
                        Modifier.fillMaxWidth().clickable { kind = k },
                        colors = if (k == kind) androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ) else androidx.compose.material3.CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text((if (k == kind) "● " else "○ ") + k.label, style = MaterialTheme.typography.titleSmall)
                            Text(k.blurb, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), kind) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Full-screen editor for a plan or note written on the phone. */
@Composable
private fun PlanEditor(e: Editing, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable(e.docId, e.packId) { mutableStateOf(e.title) }
    var text by rememberSaveable(e.docId, e.packId) { mutableStateOf(e.text) }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = title.isNotBlank() && text.isNotBlank(), onClick = { onSave(title, text) }) { Text("Save") }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(80) }, label = { Text("Title") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Fill in after each colon. Lines starting with ## are section headings — keep them, they are how the " +
                "AI finds the right part. Delete sections you don't need.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
