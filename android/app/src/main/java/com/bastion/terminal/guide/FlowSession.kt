package com.bastion.terminal.guide

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One line of the casualty log: what was done or found, and when (docs/field-guide-design.md §4). */
data class LogEntry(val timeMs: Long, val flowId: String, val nodeId: String, val text: String, val test: Boolean = false)

/** Where the casualty log is kept. */
interface LogStore {
    fun load(): List<LogEntry>
    fun save(entries: List<LogEntry>)
}

/**
 * The casualty log on the phone: files/guide/log.json. Private data: the files dir is erased by the
 * wipe PIN (security/AppLock keeps only KEEP_FILE_DIRS = models), and backups are off.
 */
class CasualtyLog(private val file: File) : LogStore {
    override fun load(): List<LogEntry> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONObject(file.readText()).getJSONArray("entries")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LogEntry(o.getLong("t"), o.optString("flow"), o.optString("node"), o.optString("text"), o.optBoolean("test"))
        }
    }.getOrDefault(emptyList())

    override fun save(entries: List<LogEntry>) {
        val a = JSONArray()
        entries.forEach { e ->
            a.put(JSONObject().put("t", e.timeMs).put("flow", e.flowId).put("node", e.nodeId).put("text", e.text).put("test", e.test))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("entries", a).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        const val DIR = "guide"
        fun of(context: Context) = CasualtyLog(File(File(context.applicationContext.filesDir, DIR), "log.json"))
    }
}

/**
 * The guide being run, kept for the whole app process (like ui/AskSession and ui/RouteSession) so
 * it survives tab switches and screen turns. Moves through the flow's nodes, keeps a Back stack,
 * and writes every confirmed step to the casualty log with its time.
 */
class FlowSession(
    private val store: LogStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    var flow by mutableStateOf<Flow?>(null); private set
    var currentId by mutableStateOf<String?>(null); private set
    private val history = ArrayDeque<String>()
    var depth by mutableStateOf(0); private set   // history size, observable
    val log = mutableStateListOf<LogEntry>().apply { addAll(store.load()) }

    val node: Node? get() = currentId?.let { flow?.nodes?.get(it) }
    val canBack: Boolean get() = depth > 0

    /** Opens [f] at its start; the same flow already open stays where it was. */
    fun open(f: Flow) {
        if (flow?.id == f.id && flow?.test == f.test && currentId != null) { flow = f; return }
        flow = f; history.clear(); depth = 0; currentId = f.start
        add(f.start, "Started guide: ${f.title}")
    }

    fun close() { flow = null; currentId = null; history.clear(); depth = 0 }

    /** Question node: picks answer [i]; logs "question — answer". */
    fun answer(i: Int) {
        val n = node ?: return
        if (n.type != NodeType.QUESTION) return
        val a = n.answers.getOrNull(i) ?: return
        add(n.id, n.log ?: "${n.text} — ${a.label}")
        go(a.next)
    }

    /** Action or tool node: confirms it (logs its log text, or the step text) and moves on. */
    fun next() {
        val n = node ?: return
        if (n.type != NodeType.ACTION && n.type != NodeType.TOOL) return
        val to = n.next ?: return
        add(n.id, n.log ?: n.text)
        go(to)
    }

    /** Goes back one step. What was logged stays logged: it happened. */
    fun back(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        currentId = prev; depth = history.size
        return true
    }

    /** From the whole-guide list: jump to any step (Back returns). */
    fun jumpTo(id: String) {
        val f = flow ?: return
        if (id !in f.nodes || id == currentId) return
        go(id)
    }

    fun restart() {
        val f = flow ?: return
        history.clear(); depth = 0; currentId = f.start
        add(f.start, "Restarted guide: ${f.title}")
    }

    /** One tap: logs "[label] at HH:mm". Returns the logged text. */
    fun timestamp(label: String): String {
        val text = "$label at ${hhmm(clock())}"
        add(currentId ?: "", text)
        return text
    }

    /** A tool event worth keeping (timer ran out, metronome started). */
    fun note(text: String) = add(currentId ?: "", text)

    fun clearLog() { log.clear(); store.save(emptyList()) }

    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }.format(Date(ms))

    private fun go(to: String) {
        currentId?.let { history.addLast(it) }
        depth = history.size
        currentId = to
    }

    private fun add(nodeId: String, text: String) {
        val f = flow ?: return
        log += LogEntry(clock(), f.id, nodeId, text, f.test)
        store.save(log.toList())
    }

    companion object {
        @Volatile private var inst: FlowSession? = null
        fun get(context: Context): FlowSession =
            inst ?: synchronized(this) { inst ?: FlowSession(CasualtyLog.of(context)).also { inst = it } }
    }
}
