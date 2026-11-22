package com.bastion.terminal.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.terminal.device.DeviceManager

/** Kiosk setting: lock the phone to BASTION and its companion apps. */
object Kiosk {
    private fun prefs(c: Context) = c.getSharedPreferences("device", Context.MODE_PRIVATE)
    fun isOn(c: Context): Boolean = prefs(c).getBoolean("kiosk", false)
    fun set(c: Context, on: Boolean) = prefs(c).edit().putBoolean("kiosk", on).apply()

    /** Applies the setting to this activity. Only does anything when BASTION is device manager. */
    fun sync(activity: Activity) {
        if (!DeviceManager.isOwner(activity)) return
        runCatching { if (isOn(activity)) activity.startLockTask() else activity.stopLockTask() }
    }
}

/** Home card: what BASTION controls on this phone, in plain words. */
@Composable
fun DeviceCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = remember(tick) { DeviceManager.isOwner(ctx) }
    var kiosk by remember(tick) { mutableStateOf(Kiosk.isOn(ctx)) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmRelease by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (owner) "● " else "○ ") + "Device manager",
                style = MaterialTheme.typography.titleMedium,
                color = if (owner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (owner) "BASTION manages this phone: it installs the companion apps from the kit folder, grants " +
                    "their permissions, and is the home screen."
                else "BASTION is an ordinary app on this phone. On a dedicated phone, the PC setup tool can make it " +
                    "the device manager (needs a factory-reset phone with no accounts), so it installs and sets up " +
                    "everything for you.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (owner) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lines = DeviceManager.apply(ctx); tick++ }) { Text("Apply setup") }
                    OutlinedButton(onClick = { lines = DeviceManager.installKit(ctx) }) { Text("Install kit apps") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock phone to BASTION", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Only BASTION, Meshtastic, ATAK, maps, Kiwix, Settings and the file picker can be opened.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = kiosk, onCheckedChange = {
                        kiosk = it
                        Kiosk.set(ctx, it)
                        (ctx as? Activity)?.let(Kiosk::sync)
                    })
                }
                TextButton(onClick = { confirmRelease = true }) { Text("Stop managing this phone") }
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (confirmRelease) AlertDialog(
        onDismissRequest = { confirmRelease = false },
        title = { Text("Stop managing this phone?") },
        text = { Text("BASTION gives up the device-manager role and stops being the home screen. Your data stays. Getting the role back needs the PC setup tool and a factory reset.") },
        confirmButton = {
            TextButton(onClick = {
                confirmRelease = false
                Kiosk.set(ctx, false)
                (ctx as? Activity)?.let { runCatching { it.stopLockTask() } }
                lines = listOf(DeviceManager.release(ctx)); tick++
            }) { Text("Stop managing") }
        },
        dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Cancel") } },
    )
}
