package com.bastion.terminal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bastion.terminal.calc.Angles
import com.bastion.terminal.calc.WaterBleach

/** Bleach dose for drinking water, computed by `calc/WaterBleach` from the EPA and CDC tables. */
@Composable
fun BleachCalculator(modifier: Modifier = Modifier) {
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(WaterBleach.Unit.GALLON) }
    var strength by rememberSaveable { mutableStateOf(WaterBleach.Strength.SIX) }
    var cloudy by rememberSaveable { mutableStateOf(false) }
    val result = amount.trim().replace(',', '.').toDoubleOrNull()?.let { WaterBleach.dose(it, unit, strength, cloudy) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Water: bleach calculator", style = MaterialTheme.typography.titleMedium)
            Text(
                "Computed from the EPA (2026) and CDC (2024) tables, not by the AI.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.take(7) },
                label = { Text("Amount of water") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WaterBleach.Unit.entries.forEach { u ->
                    FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bleach strength (on the label):", style = MaterialTheme.typography.bodySmall)
                WaterBleach.Strength.entries.forEach { s ->
                    FilterChip(selected = strength == s, onClick = { strength = s }, label = { Text(s.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = cloudy, onCheckedChange = { cloudy = it })
                Text("Water is cloudy, colored or very cold")
            }
            if (result == null) {
                Text("Enter an amount of water (more than 0, up to 1000).", color = MaterialTheme.colorScheme.error)
            } else {
                result.doses.forEach { d ->
                    Text("${d.drops} drops  (${d.agency})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                result.tableRow?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                result.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
private fun fmt(x: Double, d: Int = 2) = "%.${d}f".format(x)

@Composable
private fun <T> Chips(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { FilterChip(selected = it == selected, onClick = { onPick(it) }, label = { Text(label(it)) }) }
    }
}

@Composable
private fun NumField(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value, onValueChange = { onChange(it.take(9)) }, label = { Text(label) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    singleLine = true, modifier = Modifier.fillMaxWidth(),
)

/** MOA / MRAD conversions, mil-relation range and scope corrections, computed by `calc/Angles`. */
@Composable
fun AngleCalculator(modifier: Modifier = Modifier) {
    val modes = listOf("Convert", "Range", "Correction", "Size")
    var mode by rememberSaveable { mutableStateOf("Range") }
    var unit by rememberSaveable { mutableStateOf(Angles.AngleUnit.MRAD) }
    var angle by rememberSaveable { mutableStateOf("2") }
    var size by rememberSaveable { mutableStateOf("36") }
    var sizeUnit by rememberSaveable { mutableStateOf(Angles.Length.INCH) }
    var dist by rememberSaveable { mutableStateOf("200") }
    var distUnit by rememberSaveable { mutableStateOf(Angles.Length.YARD) }
    var click by rememberSaveable { mutableStateOf(unit.clickDefault.toString()) }
    val sizeUnits = listOf(Angles.Length.INCH, Angles.Length.CM, Angles.Length.FOOT, Angles.Length.METER)
    val distUnits = listOf(Angles.Length.YARD, Angles.Length.METER)

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Angles: MOA / MRAD", style = MaterialTheme.typography.titleMedium)
            Text(
                "Computed from the unit definitions, not by the AI. Does not account for bullet drop or wind.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Chips(modes, mode, { it }) { mode = it }
            Chips(Angles.AngleUnit.entries, unit, { it.label }) { unit = it; click = it.clickDefault.toString() }
            val lines = mutableListOf<String>()
            when (mode) {
                "Convert" -> {
                    NumField(angle, "Angle (${unit.label})") { angle = it }
                    num(angle)?.let { a ->
                        Angles.AngleUnit.entries.filter { it != unit }.forEach { u ->
                            Angles.convert(a, unit, u)?.let { lines += "${fmt(it, 3)} ${u.label}" }
                        }
                    }
                }
                "Range" -> {
                    NumField(size, "Known size of the object") { size = it }
                    Chips(sizeUnits, sizeUnit, { it.label }) { sizeUnit = it }
                    NumField(angle, "Reading in the reticle (${unit.label})") { angle = it }
                    val s = num(size); val a = num(angle)
                    if (s != null && a != null) {
                        Angles.range(s, sizeUnit, a, unit, Angles.Length.YARD)?.let { lines += "${fmt(it, 0)} yd" }
                        Angles.range(s, sizeUnit, a, unit, Angles.Length.METER)?.let { lines += "${fmt(it, 0)} m" }
                    }
                    if (lines.isNotEmpty()) lines += "Only as good as the size you assume and how precisely you read the reticle."
                }
                "Correction" -> {
                    NumField(size, "Move the impact by") { size = it }
                    Chips(sizeUnits, sizeUnit, { it.label }) { sizeUnit = it }
                    NumField(dist, "At distance") { dist = it }
                    Chips(distUnits, distUnit, { it.label }) { distUnit = it }
                    NumField(click, "Scope click value (${unit.label})") { click = it }
                    val o = num(size); val d = num(dist)
                    if (o != null && d != null) Angles.correction(o, sizeUnit, d, distUnit, unit)?.let { c ->
                        lines += "${fmt(c)} ${unit.label}"
                        num(click)?.let { Angles.clicks(c, it) }?.let { lines += "$it clicks" }
                    }
                    if (lines.isNotEmpty()) lines += "Check your scope's click value and which way each turret moves the impact."
                }
                else -> {
                    NumField(angle, "Angle (${unit.label})") { angle = it }
                    NumField(dist, "At distance") { dist = it }
                    Chips(distUnits, distUnit, { it.label }) { distUnit = it }
                    val a = num(angle); val d = num(dist)
                    if (a != null && d != null) {
                        Angles.subtension(a, unit, d, distUnit, Angles.Length.INCH)?.let { lines += "${fmt(it)} in" }
                        Angles.subtension(a, unit, d, distUnit, Angles.Length.CM)?.let { lines += "${fmt(it, 1)} cm" }
                    }
                }
            }
            if (lines.isEmpty()) Text("Enter numbers greater than 0.", color = MaterialTheme.colorScheme.error)
            else {
                lines.filter { it.first().isDigit() || it.first() == '-' }.forEach {
                    Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                lines.filterNot { it.first().isDigit() || it.first() == '-' }.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
