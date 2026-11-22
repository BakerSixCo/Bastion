package com.bastion.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bastion.terminal.security.AppLock

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Shown before anything else when an app PIN is set. */
@Composable
fun LockScreen(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("BASTION", style = MaterialTheme.typography.headlineLarge)
        PinField(pin, { pin = it; note = null }, "PIN")
        Button(
            enabled = pin.length >= 4,
            onClick = {
                when (AppLock.submit(ctx, pin)) {
                    AppLock.Result.OPEN -> onOpen()
                    // Looks exactly like a normal unlock; the process restarts with empty data.
                    AppLock.Result.WIPED -> AppLock.restartAfterWipe(ctx)
                    AppLock.Result.WRONG -> { pin = ""; note = "Wrong PIN." }
                    AppLock.Result.LOCKED_OUT -> { pin = ""; note = "Too many tries. Wait ${AppLock.lockedForSeconds()} s." }
                }
            },
        ) { Text("Open") }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Home card: set, change or remove the app PIN and the wipe PIN. */
@Composable
fun SecurityCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val hasApp = remember(tick) { AppLock.hasAppPin(ctx) }
    val hasWipe = remember(tick) { AppLock.hasWipePin(ctx) }
    var dialog by remember { mutableStateOf<String?>(null) } // "app" | "wipe" | "remove"
    var message by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (hasApp) "● " else "○ ") + "App lock",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasApp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    !hasApp -> "No PIN. Anyone who opens this phone can open BASTION."
                    hasWipe -> "A PIN is asked when BASTION opens. A wipe PIN is set: typing it instead erases your " +
                        "library, packs, mesh inbox, approved radios and TAK certificates, then opens an empty BASTION. " +
                        "The AI model and offline reference files are kept."
                    else -> "A PIN is asked when BASTION opens. No wipe PIN is set."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { message = null; dialog = "app" }) { Text(if (hasApp) "Change PIN" else "Set PIN") }
                if (hasApp) OutlinedButton(onClick = { message = null; dialog = "wipe" }) {
                    Text(if (hasWipe) "Change wipe PIN" else "Set wipe PIN")
                }
                if (hasApp) TextButton(onClick = { message = null; dialog = "remove" }) { Text("Remove") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }

    dialog?.let { kind ->
        var current by remember(kind) { mutableStateOf("") }
        var a by remember(kind) { mutableStateOf("") }
        var b by remember(kind) { mutableStateOf("") }
        var err by remember(kind) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(when (kind) { "app" -> "App PIN"; "wipe" -> "Wipe PIN"; else -> "Remove PINs" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (kind == "wipe") Text(
                        "Typing this PIN on the lock screen permanently erases BASTION's private data. It cannot be " +
                            "undone. It must be different from your app PIN — pick one you won't type by accident.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasApp) PinField(current, { current = it; err = null }, "Current app PIN")
                    if (kind != "remove") {
                        PinField(a, { a = it; err = null }, "New PIN (4–12 digits)")
                        PinField(b, { b = it; err = null }, "Repeat")
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Changing anything needs the current app PIN (a wipe PIN typed here does NOT wipe).
                    if (hasApp && !AppLock.checkAppPin(ctx, current)) { err = "Current PIN is wrong."; return@TextButton }
                    when (kind) {
                        "remove" -> { AppLock.clearPins(ctx); message = "PINs removed." }
                        else -> {
                            if (!AppLock.validPin(a)) { err = "Use 4 to 12 digits."; return@TextButton }
                            if (a != b) { err = "The two entries don't match."; return@TextButton }
                            if (kind == "app") {
                                if (hasWipe && AppLock.isWipePin(ctx, a)) { err = "That is your wipe PIN. Choose a different one."; return@TextButton }
                                AppLock.setAppPin(ctx, a); message = "App PIN set."
                            } else {
                                if (!AppLock.setWipePin(ctx, a)) { err = "It must differ from the app PIN."; return@TextButton }
                                message = "Wipe PIN set."
                            }
                        }
                    }
                    tick++; dialog = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}
