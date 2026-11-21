package com.outpostzero.terminal.ui

import android.os.StatFs
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.outpostzero.terminal.data.LibraryStore

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
        Text("OUTPOST ZERO", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Off-grid field terminal — v0.1 (library/search milestone)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(
            title = "Library",
            ok = docCount > 0,
            body = if (docCount > 0) {
                "$docCount document(s), ${docBytes / 1024} KB indexed. Full-text search works fully offline."
            } else {
                "No documents yet. Import plain-text or Markdown files in the Library tab."
            },
        )

        StatusCard(
            title = "Local AI",
            ok = false,
            body = "Not installed. No model file is present and no inference runtime ships in v0.1 — " +
                "the Ask tab returns matching library passages only. Nothing is sent to any cloud " +
                "service: this app does not declare the Internet permission at all.",
        )

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

        val freeMb = remember { StatFs(ctx.filesDir.path).availableBytes / (1024 * 1024) }
        StatusCard(
            title = "Storage",
            ok = freeMb > 500,
            body = "$freeMb MB free in app storage.",
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
