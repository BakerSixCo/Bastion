package com.bastion.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.data.LibraryStore
import com.bastion.terminal.data.SearchHit
import kotlinx.coroutines.launch

@Composable
fun AskScreen(modifier: Modifier = Modifier, onOpen: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ask", style = MaterialTheme.typography.headlineMedium)
        Card {
            Text(
                "Local AI is not installed yet. This screen searches your library and shows real " +
                    "matching passages — retrieval only, no generated answers. When a local model " +
                    "is added (Milestone 2), answers will cite these same passages.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Question or keywords") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = query.isNotBlank(),
            onClick = {
                scope.launch {
                    hits = store.search(query)
                    searched = true
                }
            },
        ) { Text("Search library") }

        if (searched && hits.isEmpty()) {
            Text(
                "No matching passages in the library — not enough local evidence to answer.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits) { h -> HitCard(h, query, onOpen) }
        }
    }
}
