package com.bastion.terminal.hw

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceCheckScreen(modifier: Modifier, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val progress = remember { MutableStateFlow("Reading device facts…") }
    val stage by progress.collectAsState()
    var report by remember { mutableStateOf<DeviceReport?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(true) }
    var stopping by remember { mutableStateOf(false) }
    var measurementJob by remember { mutableStateOf<Job?>(null) }

    suspend fun save(current: DeviceReport, onlyIfNew: Boolean = false) {
        try {
            DeviceReportStore.save(context, current, onlyIfNew)
            notice = "Report available over USB in BASTION’s device-check media folder."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = "Could not save the report to the BASTION media folder."
        }
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(context) {
        try {
            val facts = withContext(Dispatchers.IO) { DeviceCheck.readFacts(context) }
            val current = DeviceReport(facts, DeviceCheck.evaluate(facts))
            report = current
            save(current, onlyIfNew = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = "Could not read device facts."
        } finally {
            working = false
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Phone check", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("Close") }
        }
        if (working) {
            Text(if (stopping) "Cancelling measurements…" else stage)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val current = report
        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (measurementJob != null) {
                    OutlinedButton(
                        enabled = !stopping,
                        onClick = {
                            stopping = true
                            measurementJob?.cancel()
                        },
                    ) { Text("Cancel") }
                } else {
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            working = true
                            notice = null
                            progress.value = "Starting measurements…"
                            measurementJob = scope.launch {
                                try {
                                    val measurements = DeviceMeasurements.run(context) { progress.value = it }
                                    val measured = current.copy(measurements = measurements)
                                    report = measured
                                    progress.value = "Saving report…"
                                    save(measured)
                                } catch (cancelled: CancellationException) {
                                    notice = "Measurements cancelled."
                                    throw cancelled
                                } catch (_: Exception) {
                                    notice = "Could not finish the measurements."
                                } finally {
                                    stopping = false
                                    working = false
                                    measurementJob = null
                                }
                            }
                        },
                    ) { Text("Measure") }
                    OutlinedButton(
                        enabled = !working,
                        onClick = {
                            working = true
                            progress.value = "Saving report…"
                            scope.launch {
                                try {
                                    save(current)
                                } finally {
                                    working = false
                                }
                            }
                        },
                    ) { Text("Save report") }
                }
            }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        current?.let {
            Text(
                it.summary(),
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
