package com.bastion.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.mesh.MeshBridge

/**
 * Edit-then-hand-over dialog. [initial] (e.g. an AI answer) is pre-fitted to
 * one mesh message; the user can edit it and always sees the byte count.
 */
@Composable
fun MeshComposeDialog(initial: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val fitted = remember(initial) { MeshBridge.fit(initial) }
    var text by rememberSaveable(initial) { mutableStateOf(fitted.first) }
    var error by remember { mutableStateOf<String?>(null) }
    val bytes = MeshBridge.byteLength(text)
    val tooLong = bytes > MeshBridge.MAX_BYTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mesh message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    isError = tooLong,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Text(
                    "$bytes / ${MeshBridge.MAX_BYTES} bytes" +
                        if (tooLong) " — too long for one mesh message" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fitted.second) Text(
                    "Shortened to fit one mesh message (citation numbers removed). Check it still says what you mean.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Opens Meshtastic with this text. Nothing is transmitted until you press Send there. " +
                        "Mesh channels may be unencrypted — don't send secrets.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !tooLong,
                onClick = {
                    if (MeshBridge.share(ctx, text.trim())) onDismiss()
                    else error = "Could not open Meshtastic. Is it installed and enabled?"
                },
            ) { Text("Open in Meshtastic") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
