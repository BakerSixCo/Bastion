package com.bastion.terminal.ui

import androidx.compose.foundation.layout.Arrangement
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
