package com.bastion.terminal.ui

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.bastion.terminal.data.DocMeta
import com.bastion.terminal.data.ImportResult
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.data.SearchHit
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(modifier: Modifier = Modifier, onOpen: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    val tick by store.changeCounter.collectAsState()
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<DocMeta>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) { docs = store.listDocs() }
    LaunchedEffect(query, tick) {
        hits = if (query.isBlank()) emptyList() else store.search(query)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = when (val r = store.importFromUri(uri)) {
                    is ImportResult.Ok -> "Imported “${r.doc.title}” (${r.doc.sizeBytes / 1024} KB)."
                    is ImportResult.Rejected -> r.reason
                }
                busy = false
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Library", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    try {
                        picker.launch(arrayOf("text/*", "application/octet-stream"))
                    } catch (_: ActivityNotFoundException) {
                        message = "This device has no file picker app, so documents cannot be imported here."
                    }
                },
                enabled = !busy,
            ) { Text("Import document") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        message = when (val r = store.importSample()) {
                            is ImportResult.Ok -> "Added the labeled sample document."
                            is ImportResult.Rejected -> r.reason
                        }
                        busy = false
                    }
                },
                enabled = !busy,
            ) { Text("Add sample (demo)") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search the library") },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                items(docs) { d -> DocRow(d) { onOpen(d.id, "") } }
                if (docs.isEmpty()) {
                    item {
                        Text(
                            "Library is empty. Import .txt or .md files; PDF, EPUB, and ZIM are " +
                                "not supported yet and will be rejected with a clear message.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(hits) { h -> HitCard(h, query, onOpen) }
                if (hits.isEmpty()) {
                    item { Text("No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun DocRow(d: DocMeta, onClick: () -> Unit) {
    val date = remember(d.importedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(d.importedAt))
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "${d.filename} · ${d.sizeBytes / 1024} KB · imported $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
