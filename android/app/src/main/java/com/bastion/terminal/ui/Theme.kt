package com.bastion.terminal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark-first, high-contrast theme: the station is read outdoors and at night.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BC34A),
    onPrimary = Color(0xFF0B1008),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF101413),
    background = Color(0xFF0E120F),
    onBackground = Color(0xFFE6ECE6),
    surface = Color(0xFF171C18),
    onSurface = Color(0xFFE6ECE6),
    surfaceVariant = Color(0xFF232A24),
    onSurfaceVariant = Color(0xFFC3CCC3),
    error = Color(0xFFFFB74D),
    onError = Color(0xFF201303),
)

val HighlightColor = Color(0xFF9CCC65)

@Composable
fun BastionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
