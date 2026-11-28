package com.bastion.terminal.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bastion.terminal.guide.Countdown
import com.bastion.terminal.guide.Flow
import com.bastion.terminal.guide.FlowSession
import com.bastion.terminal.guide.FlowStore
import com.bastion.terminal.guide.Metronome
import com.bastion.terminal.guide.Node
import com.bastion.terminal.guide.NodeType
import com.bastion.terminal.guide.Tool
import com.bastion.terminal.guide.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

val EmergencyRed = Color(0xFFC62828)
private val BIG = 72.dp
const val TEST_BANNER = "TEST FLOW — NOT APPROVED"

/** The red EMERGENCY button at the top of Home. */
@Composable
fun EmergencyButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
        colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed, contentColor = Color.White),
    ) { Text("EMERGENCY", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

/**
 * The emergency screen: one big button per entry flow (approved flows only; debug builds also
 * test flows, marked). No typing, no AI (docs/field-guide-design.md §2). A running guide is kept by
 * [FlowSession], so leaving and coming back returns to the same step.
 */
@Composable
fun EmergencyScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val session = remember { FlowSession.get(ctx) }
    var loaded by remember { mutableStateOf<FlowStore.Loaded?>(null) }
    LaunchedEffect(Unit) { loaded = withContext(Dispatchers.IO) { FlowStore.load(ctx) } }
    var showLog by rememberSaveable { mutableStateOf(false) }

    session.flow?.let { f ->
        FlowPlayer(session, f, onExit = { session.close() })
        return
    }
    if (showLog) {
        BackHandler { showLog = false }
        LogView(session, test = false, onClose = { showLog = false })
        return
    }
    BackHandler { onClose() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EMERGENCY", style = MaterialTheme.typography.headlineMedium, color = EmergencyRed,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close", fontSize = 18.sp) }
        }
        val l = loaded
        if (l == null) { Text("Loading guides…"); return@Column }
        val approved = l.flows.filter { it.entry && !it.test }
        val tests = l.flows.filter { it.entry && it.test }
        if (approved.isEmpty()) Text("No approved emergency guides yet.", style = MaterialTheme.typography.titleLarge)
        approved.forEach { f -> FlowButton(f) { session.open(f) } }
        if (tests.isNotEmpty()) {
            Text("Debug build — test flows", style = MaterialTheme.typography.titleMedium, color = EmergencyRed)
            tests.forEach { f -> FlowButton(f) { session.open(f) } }
        }
        OutlinedButton(onClick = { showLog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text("Casualty log (${session.log.size})", fontSize = 20.sp)
        }
        if (l.rejected.isNotEmpty()) {
            Text("Refused (failed the checks, not shown):", color = MaterialTheme.colorScheme.error)
            l.rejected.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun FlowButton(f: Flow, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        colors = if (f.test) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(f.title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (f.test) Text("TEST — NOT APPROVED", color = EmergencyRed, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TestBanner() {
    Text(
        TEST_BANNER,
        Modifier.fillMaxWidth().background(EmergencyRed).padding(8.dp),
        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
    )
}

private enum class View { STEP, WHOLE, LOG }

/** One step at a time, huge text, big buttons; Back, whole guide, log, source per step. */
@Composable
fun FlowPlayer(session: FlowSession, flow: Flow, onExit: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(View.STEP) }
    var sourceOf by remember { mutableStateOf<Node?>(null) }
    // The screen stays on while a guide is open.
    val rootView = LocalView.current
    DisposableEffect(Unit) { rootView.keepScreenOn = true; onDispose { rootView.keepScreenOn = false } }

    BackHandler {
        when {
            view != View.STEP -> view = View.STEP
            session.canBack -> session.back()
            else -> onExit()
        }
    }
    if (view == View.LOG) { LogView(session, test = flow.test, onClose = { view = View.STEP }); return }

    Column(Modifier.fillMaxSize()) {
        if (flow.test) TestBanner()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flow.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 56.dp)) { Text("Exit", fontSize = 18.sp) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (view == View.WHOLE) WholeGuide(session, flow) { view = View.STEP }
            else session.node?.let { StepView(session, flow, it, onSource = { n -> sourceOf = n }) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val mod = Modifier.weight(1f).heightIn(min = 64.dp)
            OutlinedButton(onClick = { if (view != View.STEP) view = View.STEP else session.back() },
                enabled = view != View.STEP || session.canBack, modifier = mod) { Text("Back", fontSize = 18.sp) }
            OutlinedButton(onClick = { view = if (view == View.WHOLE) View.STEP else View.WHOLE }, modifier = mod) {
                Text(if (view == View.WHOLE) "Step" else "Whole guide", fontSize = 16.sp)
            }
            OutlinedButton(onClick = { view = View.LOG }, modifier = mod) { Text("Log", fontSize = 18.sp) }
        }
    }
    sourceOf?.let { n -> SourceDialog(flow, n) { sourceOf = null } }
}

@Composable
private fun StepView(session: FlowSession, flow: Flow, n: Node, onSource: (Node) -> Unit) {
    Text(n.text, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
    n.warnings.forEach { Text("⚠ $it", fontSize = 20.sp, color = MaterialTheme.colorScheme.error) }
    if (n.image != null) Text("Picture ${n.image}: not available in this build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (n.quoteRefs.isNotEmpty()) {
        val docs = n.quoteRefs.mapNotNull { flow.quotes[it]?.source }.distinct()
        OutlinedButton(onClick = { onSource(n) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Source: " + docs.joinToString("; "), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (n.type == NodeType.TOOL) n.tool?.let { ToolWidget(session, n, it) }
    when (n.type) {
        NodeType.QUESTION -> n.answers.forEachIndexed { i, a ->
            Button(onClick = { session.answer(i) }, modifier = Modifier.fillMaxWidth().heightIn(min = BIG)) {
                Text(a.label, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        NodeType.ACTION, NodeType.TOOL -> Button(onClick = { session.next() }, modifier = Modifier.fillMaxWidth().heightIn(min = BIG)) {
            Text(if (n.type == NodeType.ACTION) "Done" else "Next step", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        NodeType.END -> {
            Text("End of guide.", fontSize = 22.sp)
            Button(onClick = { session.restart() }, modifier = Modifier.fillMaxWidth().heightIn(min = BIG)) { Text("Start again", fontSize = 22.sp) }
        }
        NodeType.UNKNOWN -> Text("This step cannot be shown.")
    }
}

@Composable
private fun WholeGuide(session: FlowSession, flow: Flow, onPicked: () -> Unit) {
    Text("Whole guide — tap a step to go to it", style = MaterialTheme.typography.titleMedium)
    flow.orderedNodes.forEachIndexed { i, n ->
        val here = n.id == session.currentId
        Card(Modifier.fillMaxWidth().clickable { session.jumpTo(n.id); onPicked() }) {
            Column(Modifier.padding(12.dp)) {
                Text("${i + 1}. " + (if (here) "▶ " else "") + n.text, fontSize = 18.sp,
                    fontWeight = if (here) FontWeight.Bold else FontWeight.Normal)
                if (n.type == NodeType.QUESTION) Text(n.answers.joinToString("  ·  ") { a ->
                    "${a.label} → ${flow.orderedNodes.indexOfFirst { it.id == a.next } + 1}" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SourceDialog(flow: Flow, n: Node, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (flow.test) TestBanner()
                n.quoteRefs.mapNotNull { flow.quotes[it] }.forEach { q ->
                    Text("“${q.text}”")
                    Text(q.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun LogView(session: FlowSession, test: Boolean, onClose: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (test) TestBanner()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Casualty log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close", fontSize = 18.sp) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.log.isEmpty()) Text("Nothing logged yet.")
            session.log.forEach { e ->
                Text(session.hhmm(e.timeMs) + "  " + (if (e.test) "[TEST] " else "") + e.text, fontSize = 18.sp)
            }
            Text("Kept on this phone only; erased by the wipe PIN.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.log.isNotEmpty()) OutlinedButton(
            onClick = { if (confirmClear) { session.clearLog(); confirmClear = false } else confirmClear = true },
            modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 56.dp),
        ) { Text(if (confirmClear) "Tap again to erase the log" else "Clear log") }
    }
}

// ---- Tools inside steps (docs/field-guide-design.md §3). All numbers come from the approved flow. ----

@Composable
private fun ToolWidget(session: FlowSession, n: Node, t: Tool) {
    when (t.kind) {
        ToolKind.METRONOME -> MetronomeTool(session, n, t)
        ToolKind.TIMER -> TimerTool(session, n, t)
        ToolKind.TIMESTAMP -> TimestampTool(session, t)
        ToolKind.UNKNOWN -> Text("Unknown tool.")
    }
}

/** Short beeps from the phone's tone generator (no sound files); silent if the phone refuses one. */
@Composable
private fun rememberTones(): ToneGenerator? {
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    return tone
}

@Composable
private fun MetronomeTool(session: FlowSession, n: Node, t: Tool) {
    val m = remember(n.id) { Metronome(t.rateBpm, t.compressions, t.breaths, t.cycleSeconds) }
    val tone = rememberTones()
    var running by remember(n.id) { mutableStateOf(false) }
    var resume by remember(n.id) { mutableIntStateOf(0) }
    var count by remember(n.id) { mutableIntStateOf(0) }
    var breathsDue by remember(n.id) { mutableStateOf(false) }
    var cycleMsg by remember(n.id) { mutableStateOf("") }
    var pulse by remember(n.id) { mutableIntStateOf(0) }
    LaunchedEffect(running, resume) {
        while (running && isActive) {
            val due = m.nextDueAt() ?: break
            delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            for (e in m.tick(SystemClock.elapsedRealtime())) when (e) {
                is Metronome.Event.Beat -> { count = e.count; pulse++; tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
                is Metronome.Event.Breaths -> { breathsDue = true; tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300) }
                is Metronome.Event.Cycle -> {
                    cycleMsg = "Cycle ${e.cycle} done (every ${t.cycleSeconds} s)"
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                }
            }
        }
    }
    DisposableEffect(n.id) { onDispose { m.stop() } }
    val scale by animateFloatAsState(if (pulse % 2 == 0) 1f else 1.15f, tween(80), label = "pulse")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Metronome ${fmt(t.rateBpm)} per minute" + (if (t.compressions > 0) ", ${t.compressions} then ${t.breaths} breaths" else ""),
                style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(120.dp).scale(if (running && !breathsDue) scale else 1f)
                .background(if (running) EmergencyRed else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(if (breathsDue) "${t.breaths}" else "$count", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            if (t.compressions > 0 && !breathsDue) Text("$count of ${t.compressions}", fontSize = 22.sp)
            if (breathsDue) {
                Text("Give ${t.breaths} breaths", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Button(onClick = { m.breathsDone(SystemClock.elapsedRealtime()); breathsDue = false; count = 0; resume++ },
                    modifier = Modifier.fillMaxWidth().heightIn(min = BIG)) { Text("Breaths done", fontSize = 24.sp) }
            }
            if (cycleMsg.isNotEmpty()) Text(cycleMsg, fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
            Button(
                onClick = {
                    if (running) { running = false; m.stop(); session.note("Metronome stopped (${m.rounds} rounds)") }
                    else { m.start(SystemClock.elapsedRealtime()); count = 0; breathsDue = false; cycleMsg = ""; running = true; session.note("Metronome started") }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = BIG),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) { Text(if (running) "Stop metronome" else "Start metronome", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun TimerTool(session: FlowSession, n: Node, t: Tool) {
    val c = remember(n.id) { Countdown(t.seconds) }
    val tone = rememberTones()
    var left by remember(n.id) { mutableIntStateOf(t.seconds) }
    var started by remember(n.id) { mutableIntStateOf(0) }
    var done by remember(n.id) { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (started == 0) return@LaunchedEffect
        while (isActive) {
            left = c.remaining(SystemClock.elapsedRealtime())
            if (left == 0) {
                done = true; tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
                session.note("Timer done" + (if (t.label.isNotBlank()) ": ${t.label}" else "")); break
            }
            delay(200)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("%d:%02d".format(left / 60, left % 60), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            if (done) Text("Time is up" + (if (t.label.isNotBlank()) ": ${t.label}" else ""), fontSize = 26.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Button(onClick = { c.start(SystemClock.elapsedRealtime()); done = false; started++ },
                modifier = Modifier.fillMaxWidth().heightIn(min = BIG),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text(if (c.running) "Start again" else "Start timer", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun TimestampTool(session: FlowSession, t: Tool) {
    var last by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { last = session.timestamp(t.label) }, modifier = Modifier.fillMaxWidth().heightIn(min = BIG),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Log: ${t.label}", fontSize = 22.sp)
            }
            if (last.isNotEmpty()) Text("Logged: $last", fontSize = 20.sp)
        }
    }
}

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
