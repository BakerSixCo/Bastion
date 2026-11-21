package com.outpostzero.terminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.outpostzero.terminal.data.SearchHit
import com.outpostzero.terminal.data.TextUtil

/** Renders a search hit card; tapping opens the source document. */
@Composable
fun HitCard(hit: SearchHit, query: String, onOpen: (Long, String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.docId, query) }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                annotateSnippet(hit.snippet, HighlightColor),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Converts snippet() marker characters into bold, highlighted spans. */
fun annotateSnippet(s: String, color: Color): AnnotatedString = buildAnnotatedString {
    var open = false
    for (ch in s) {
        when (ch) {
            TextUtil.MARK_START -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color))
                open = true
            }
            TextUtil.MARK_END -> {
                if (open) {
                    pop()
                    open = false
                }
            }
            else -> append(ch)
        }
    }
    if (open) pop()
}
