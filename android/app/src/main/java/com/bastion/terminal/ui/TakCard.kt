package com.bastion.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.link.LinkClient
import kotlinx.coroutines.launch
import com.bastion.terminal.link.TakContact
import java.util.Locale

/**
 * Units and TAK chat, straight from the Meshtastic app's local TAK server on
 * this phone. Honest about every state: not set up, server off, refused.
 */
@Composable
fun TakCard() {
    val ctx = LocalContext.current
    val link = remember { LinkClient.get(ctx) }
    val state by link.state.collectAsState()
    val contacts by link.contacts.collectAsState()
    val chats by link.chats.collectAsState()
    LaunchedEffect(Unit) { link.start() }
    var draft by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) scope.launch { note = link.importPackage(uri) } }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) scope.launch { note = link.exportForAtak(uri) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (state.connected) "● " else "○ ") + "Units and TAK",
                style = MaterialTheme.typography.titleMedium,
                color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when (state.state) {
                    "not_set_up" -> "Not set up. In Meshtastic → Settings → Advanced → TAK Server: switch on the server " +
                        "and “Mesh to CoT Converter”, tap “Export TAK Data Package”, then import that .zip here."
                    "connecting" -> "Connecting to the Meshtastic app's local TAK server…"
                    "connected" -> "Connected to the TAK server on this phone. Positions come from Meshtastic's " +
                        "“Mesh to CoT Converter” and from ATAK users on your TAK channel."
                    "idle" -> "Starting…"
                    else -> "Not connected. " + state.detail
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.connected) OutlinedButton(onClick = {
                    note = null
                    try { picker.launch(arrayOf("*/*")) } catch (_: android.content.ActivityNotFoundException) {
                        note = "This phone has no file picker app."
                    }
                }) { Text(if (state.state == "not_set_up") "Import TAK data package" else "Re-import package") }
                if (state.state != "not_set_up") androidx.compose.material3.TextButton(onClick = { link.forget() }) { Text("Forget") }
            }
            if (state.state != "not_set_up" && link.canExportForAtak()) {
                OutlinedButton(onClick = {
                    note = null
                    try { saver.launch("BASTION_ATAK_package.zip") } catch (_: android.content.ActivityNotFoundException) {
                        note = "This phone has no file picker app."
                    }
                }) { Text("Save package for ATAK") }
                Text(
                    "ATAK ignores the package as Meshtastic exports it. This saves a copy ATAK accepts — import it in ATAK once.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let { if (state.connected) Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Text(
                "This is BASTION's only network connection, and it stays inside this phone (127.0.0.1).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.connected) note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (state.connected) {
                Text("Units (${contacts.size})", style = MaterialTheme.typography.labelLarge)
                if (contacts.isEmpty()) Text(
                    "None reported yet. Nodes appear when they send a position.",
                    style = MaterialTheme.typography.bodySmall,
                )
                contacts.take(30).forEach { c -> ContactRow(c) }
                HorizontalDivider()
                Text("TAK chat", style = MaterialTheme.typography.labelLarge)
                chats.take(20).forEach { m ->
                    Text("${m.sender}: ${m.text}", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it; note = null },
                    label = { Text("Message to TAK users") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            if (link.sendChat(draft.trim())) draft = "" else note = "Not sent — the TAK server isn't connected."
                        },
                    ) { Text("Send TAK chat") }
                }
                Text(
                    "TAK chat reaches ATAK/iTAK users on your TAK channel. People with only the Meshtastic app " +
                        "don't see it — use “Write message” for them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ContactRow(c: TakContact) {
    val age = ((System.currentTimeMillis() - c.seenAt) / 1000).coerceAtLeast(0)
    val seen = if (age < 90) "just now" else "${age / 60} min ago"
    Column {
        Text(
            c.callsign + (c.team?.let { " · $it" } ?: "") + (c.battery?.let { " · $it%" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            (if (c.hasPosition) String.format(Locale.US, "%.5f, %.5f", c.lat, c.lon) else "no position") +
                " · seen $seen" + (if (c.isMeshNode) " · mesh node" else ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
