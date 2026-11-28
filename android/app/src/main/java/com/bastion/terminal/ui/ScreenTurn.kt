package com.bastion.terminal.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Which way up BASTION opens. Upright is the default: it is how a phone is
 * held in one hand, and the keyboard leaves room to read. Sideways suits a
 * phone in a mount or a session on the map. BASTION stays the way it is set
 * until the owner changes it; "Turns with the phone" hands it back to Android.
 */
object ScreenTurn {
    enum class Mode(val label: String, val request: Int) {
        UPRIGHT("Upright", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        SIDEWAYS("Sideways", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        AUTO("Turns with the phone", ActivityInfo.SCREEN_ORIENTATION_USER),
    }

    private fun prefs(c: Context) = c.getSharedPreferences("device", Context.MODE_PRIVATE)

    fun mode(c: Context): Mode =
        Mode.entries.firstOrNull { it.name == prefs(c).getString("screen", null) } ?: Mode.UPRIGHT

    fun set(activity: Activity, mode: Mode) {
        prefs(activity).edit().putString("screen", mode.name).apply()
        apply(activity)
    }

    /** Called before the first frame, so BASTION never opens one way and then flips. */
    fun apply(activity: Activity) {
        val want = mode(activity).request
        if (activity.requestedOrientation != want) activity.requestedOrientation = want
    }
}

/** Home card: choose which way up BASTION opens. */
@Composable
fun ScreenCard() {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(ScreenTurn.mode(ctx)) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Screen", style = MaterialTheme.typography.titleMedium)
            Text(
                "BASTION always opens this way and stays this way.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenTurn.Mode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m; (ctx as? Activity)?.let { ScreenTurn.set(it, m) } },
                        label = { Text(m.label) },
                    )
                }
            }
        }
    }
}
