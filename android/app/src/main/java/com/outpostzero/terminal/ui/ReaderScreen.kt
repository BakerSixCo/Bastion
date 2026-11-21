package com.outpostzero.terminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.outpostzero.terminal.data.DocMeta
import com.outpostzero.terminal.data.LibraryStore
import com.outpostzero.terminal.data.TextUtil

private const val MAX_DISPLAY_CHARS = 400_000

@Composable
fun ReaderScreen(docId: Long, query: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LibraryStore.get(ctx) }
    var meta by remember { mutableStateOf<DocMeta?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(docId) {
        meta = store.docMeta(docId)
        text = store.docText(docId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text(
                meta?.title ?: "…",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }
        meta?.let {
            Text(
                it.provenance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        val body = text
        if (body == null) {
            Text("Loading…")
        } else {
            val truncated = body.length > MAX_DISPLAY_CHARS
            val display = if (truncated) body.take(MAX_DISPLAY_CHARS) else body
            val ranges = remember(display, query) { TextUtil.highlightRanges(display, query) }
            val annotated = remember(display, ranges) {
                buildAnnotatedString {
                    append(display)
                    for (r in ranges) {
                        addStyle(
                            SpanStyle(color = HighlightColor, fontWeight = FontWeight.Bold),
                            r.first,
                            r.last + 1,
                        )
                    }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (truncated) {
                    Text(
                        "Large document: showing the first 400,000 characters.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(annotated, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
