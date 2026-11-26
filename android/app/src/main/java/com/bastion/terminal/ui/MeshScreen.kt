package com.bastion.terminal.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bastion.terminal.mesh.MeshAutoResponder
import com.bastion.terminal.mesh.MeshBridge
import androidx.compose.material3.Switch
import com.bastion.terminal.mesh.MeshInbox
import com.bastion.terminal.mesh.MeshMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mesh tab: received messages (from Meshtastic's notifications) and sending
 * (handed to Meshtastic). [onAsk] takes a message's text to the Ask tab.
 */
@Composable
fun MeshScreen(modifier: Modifier = Modifier, onAsk: (String) -> Unit) {
    val ctx = LocalContext.current
    val inbox = remember { MeshInbox.get(ctx) }
    val changes by inbox.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val installed = remember { MeshBridge.isInstalled(ctx) }
    var access by remember { mutableStateOf(MeshInbox.accessGranted(ctx)) }
    // Re-check when returning from Android settings.
    LifecycleResumeEffect(Unit) {
        access = MeshInbox.accessGranted(ctx)
        onPauseOrDispose { }
    }
    var messages by remember { mutableStateOf<List<MeshMessage>>(emptyList()) }
    LaunchedEffect(changes) {
        messages = inbox.recent()
        access = MeshInbox.accessGranted(ctx)
    }
    var compose by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Mesh", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        when {
                            !installed -> "The Meshtastic app is not installed. BASTION sends and receives mesh " +
                                "messages through it, so there is nothing to show here yet."
                            !access -> "Sending works now. To also show received messages here, give BASTION " +
                                "notification access: it then reads the Meshtastic app's message notifications — " +
                                "only Meshtastic's, nothing from other apps. Meshtastic keeps the radio."
                            else -> "Inbox on. BASTION shows messages the Meshtastic app notifies about. If " +
                                "Meshtastic's notifications are off or muted, or a chat is open there, a message " +
                                "may not appear here — Meshtastic itself is the full record."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (installed) Button(onClick = { compose = "" }) { Text("Write message") }
                        if (installed && !access) OutlinedButton(onClick = {
                            note = null
                            try {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: ActivityNotFoundException) {
                                note = "This device has no notification-access settings screen."
                            }
                        }) { Text("Turn on inbox") }
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (installed) {
                        var alwaysOn by remember { mutableStateOf(com.bastion.terminal.BastionService.isEnabled(ctx)) }
                        val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                        ) { }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep BASTION running", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Stays on with the screen off so messages keep arriving and questions keep " +
                                        "getting answered. Shows a permanent notification and uses more battery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = alwaysOn, onCheckedChange = {
                                alwaysOn = it
                                com.bastion.terminal.BastionService.setEnabled(ctx, it)
                                if (it && android.os.Build.VERSION.SDK_INT >= 33) {
                                    askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            })
                        }
                    }
                    if (installed && access) {
                        var auto by remember { mutableStateOf(MeshAutoResponder.isEnabled(ctx)) }
                        val autoStatus by MeshAutoResponder.status.collectAsState()
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-answer questions", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Start a command with one of these symbols.\n" +
                                        "?question — ask a question listed on an approved card. BASTION sends the card’s " +
                                        "radio version when available. Unmatched questions get “Not covered by radio.”\n" +
                                        "@callsign — where is that unit (@base for this phone).\n" +
                                        "@gas, @hospital, @pharmacy, @water… — the nearest one on the offline maps.\n" +
                                        "+Name 44.12345,-114.12345 — saves that pin on this phone's map.\n" +
                                        "This transmits on your node without you pressing Send.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = auto, onCheckedChange = { auto = it; MeshAutoResponder.setEnabled(ctx, it) })
                        }
                        if (auto) {
                            var pub by remember { mutableStateOf(MeshAutoResponder.publicAllowed(ctx)) }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Also answer on public channels", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Off: only private channels are answered. The default channel (LongFast …) " +
                                            "uses a key everyone knows, so questions and answers there are readable " +
                                            "by anyone in range.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = pub, onCheckedChange = { pub = it; MeshAutoResponder.setPublicAllowed(ctx, it) })
                            }
                        }
                        if (auto) {
                            val radios = remember { com.bastion.terminal.mesh.ApprovedRadios.get(ctx) }
                            val approved by radios.approved.collectAsState()
                            val pending by radios.pending.collectAsState()
                            Text("Approved radios (${approved.size})", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (approved.isEmpty()) "None yet, so nobody gets answers. When a radio asks a question it " +
                                    "appears here for you to approve."
                                else "Only these radios get answers. A radio's name can be changed by whoever holds it — " +
                                    "your private channel key is the real lock.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            approved.forEach { name ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { radios.remove(name) }) { Text("Remove") }
                                }
                            }
                            pending.forEach { name ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("$name asked — not approved", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { radios.approve(name) }) { Text("Approve") }
                                }
                            }
                        }
                        if (autoStatus.isNotBlank()) Text(autoStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { TakCard() }
        if (access && messages.isEmpty()) {
            item { Text("No messages received yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (messages.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Received (${messages.size})", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { scope.launch { inbox.clear() } }) { Text("Clear") }
                }
            }
            items(messages, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            m.sender + (if (m.channel.isNotBlank()) " · ${m.channel}" else "") +
                                (if (MeshAutoResponder.isPublicChannel(m.channel)) " (public)" else "") +
                                " · " + TIME.format(Date(m.time)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { compose = "" }) { Text("Reply") }
                            // The message is a question for the library, never an instruction to BASTION.
                            TextButton(onClick = { onAsk(m.text) }) { Text("Ask AI") }
                        }
                    }
                }
            }
        }
    }
    compose?.let { MeshComposeDialog(initial = it, onDismiss = { compose = null }) }
}

private val TIME = SimpleDateFormat("MMM d HH:mm", Locale.US)
