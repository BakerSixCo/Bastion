package com.bastion.terminal.guide

import org.json.JSONArray
import org.json.JSONObject

/**
 * A guided flow: an emergency guide run one step at a time (docs/field-guide-design.md §2).
 * Data, not code — built and checked on the PC by tools/knowledge/flows.pl (format:
 * tools/knowledge/flows/README.md). Every step's text is shown exactly as written.
 */
data class Flow(
    val id: String,
    val title: String,
    val entry: Boolean,
    val status: String,
    val start: String,
    val nodes: Map<String, Node>,
    val quotes: Map<String, Quote>,
    val links: List<String>,
    /** Loaded from the debug-only test folder: shown with a TEST FLOW banner, never approved. */
    val test: Boolean = false,
) {
    data class Quote(val id: String, val doc: String, val page: Int?, val text: String) {
        /** Document name without its extension, for display. */
        val source: String get() = doc.substringBeforeLast('.') + (page?.let { ", p. $it" } ?: "")
    }

    /** Nodes in a stable reading order: from [start], breadth first, ends last, then any left over. */
    val orderedNodes: List<Node>
        get() {
            val seen = LinkedHashSet<String>()
            val todo = ArrayDeque<String>()
            if (start in nodes) { todo.add(start); seen.add(start) }
            while (todo.isNotEmpty()) {
                val n = nodes[todo.removeFirst()] ?: continue
                for (t in n.targets) if (t in nodes && seen.add(t)) todo.add(t)
            }
            val reached = seen.mapNotNull { nodes[it] }
            return reached.filter { it.type != NodeType.END } + reached.filter { it.type == NodeType.END } +
                nodes.values.filter { it.id !in seen }
        }
}

enum class NodeType { QUESTION, ACTION, TOOL, END, UNKNOWN }

data class Node(
    val id: String,
    val type: NodeType,
    val text: String,
    val quoteRefs: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val image: String? = null,
    val answers: List<Answer> = emptyList(),
    val next: String? = null,
    val tool: Tool? = null,
    val log: String? = null,
) {
    data class Answer(val label: String, val next: String)

    /** Every node this one can go to. */
    val targets: List<String> get() = answers.map { it.next } + listOfNotNull(next)
}

enum class ToolKind { METRONOME, TIMER, TIMESTAMP, UNKNOWN }

data class Tool(
    val kind: ToolKind,
    val rateBpm: Double = 0.0,
    val compressions: Int = 0,
    val breaths: Int = 0,
    val cycleSeconds: Int = 0,
    val seconds: Int = 0,
    val label: String = "",
)

/** Reads flow JSON (one flow, or a bundle `{"flows": [...]}`). Throws on malformed JSON or missing fields. */
object FlowParser {

    fun parseBundle(json: String, test: Boolean = false): List<Flow> {
        val a = JSONObject(json).optJSONArray("flows") ?: return emptyList()
        return (0 until a.length()).map { parse(a.getJSONObject(it), test) }
    }

    fun parse(json: String, test: Boolean = false): Flow = parse(JSONObject(json), test)

    fun parse(o: JSONObject, test: Boolean = false): Flow {
        val nodesJson = o.getJSONObject("nodes")
        val nodes = LinkedHashMap<String, Node>()
        for (key in nodesJson.keys()) nodes[key] = node(key, nodesJson.getJSONObject(key))
        val quotes = LinkedHashMap<String, Flow.Quote>()
        val qa = o.optJSONArray("quotes") ?: JSONArray()
        for (i in 0 until qa.length()) {
            val q = qa.getJSONObject(i)
            val id = q.getString("id")
            quotes[id] = Flow.Quote(id, q.optString("doc"), q.optInt("page", 0).takeIf { it > 0 }, q.optString("text"))
        }
        return Flow(
            id = o.getString("id"),
            title = o.getString("title"),
            entry = o.optBoolean("entry", false),
            status = o.optString("status"),
            start = o.getString("start"),
            nodes = nodes,
            quotes = quotes,
            links = o.optJSONArray("links").strings(),
            test = test,
        )
    }

    private fun node(id: String, o: JSONObject): Node {
        val answers = o.optJSONArray("answers")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { x -> Node.Answer(x.optString("label"), x.optString("next")) } }
        } ?: emptyList()
        return Node(
            id = id,
            type = when (o.optString("type")) {
                "question" -> NodeType.QUESTION; "action" -> NodeType.ACTION
                "tool" -> NodeType.TOOL; "end" -> NodeType.END; else -> NodeType.UNKNOWN
            },
            text = o.optString("text"),
            quoteRefs = o.optJSONArray("quote_refs").strings(),
            warnings = o.optJSONArray("warnings").strings(),
            image = o.optString("image").ifBlank { null },
            answers = answers,
            next = if (o.has("next") && !o.isNull("next")) o.getString("next") else null,
            tool = o.optJSONObject("tool")?.let(::tool),
            log = o.optString("log").ifBlank { null },
        )
    }

    private fun tool(o: JSONObject) = Tool(
        kind = when (o.optString("kind")) {
            "metronome" -> ToolKind.METRONOME; "timer" -> ToolKind.TIMER
            "timestamp" -> ToolKind.TIMESTAMP; else -> ToolKind.UNKNOWN
        },
        rateBpm = o.optDouble("rate_bpm", 0.0),
        compressions = o.optInt("compressions", 0),
        breaths = o.optInt("breaths", 0),
        cycleSeconds = o.optInt("cycle_seconds", 0),
        seconds = o.optInt("seconds", 0),
        label = o.optString("label"),
    )

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/**
 * The structural rules of tools/knowledge/flows.pl, repeated on the phone so a broken flow is
 * refused at load (and reported), never shown. Quotes are not re-checked here: that is the PC's job.
 */
object FlowValidator {

    /** Problems found; empty = the flow may be shown. */
    fun problems(f: Flow): List<String> {
        val p = mutableListOf<String>()
        if (f.id.isBlank()) p += "no id"
        if (f.title.isBlank()) p += "no title"
        if (f.status !in setOf("draft", "reviewed", "approved")) p += "status must be draft, reviewed or approved"
        if (f.nodes.isEmpty()) return p + "no nodes"
        for (n in f.nodes.values) {
            if (n.text.isBlank()) p += "node ${n.id}: no text"
            when (n.type) {
                NodeType.UNKNOWN -> p += "node ${n.id}: type must be question, action, tool or end"
                NodeType.QUESTION -> {
                    if (n.answers.size < 2) p += "node ${n.id}: a question needs at least 2 answers"
                    if (n.answers.any { it.label.isBlank() }) p += "node ${n.id}: an answer has no label"
                    if (n.next != null) p += "node ${n.id}: a question goes on by its answers, not next"
                }
                NodeType.END -> if (n.next != null || n.answers.isNotEmpty()) p += "node ${n.id}: an end has no next or answers"
                NodeType.ACTION, NodeType.TOOL -> {
                    if (n.next == null) p += "node ${n.id}: needs next"
                    if (n.answers.isNotEmpty()) p += "node ${n.id}: only a question has answers"
                }
            }
            if (n.type == NodeType.TOOL) p += toolProblems(n)
            else if (n.tool != null) p += "node ${n.id}: only a tool node has tool"
            for (t in n.targets) if (t !in f.nodes) p += "node ${n.id}: goes to '$t', which is not a node"
            if (n.type != NodeType.END && n.quoteRefs.isEmpty()) p += "node ${n.id}: needs at least one quote_ref"
            for (r in n.quoteRefs) if (r !in f.quotes) p += "node ${n.id}: quote_ref '$r' is not a quote of this flow"
        }
        if (p.isNotEmpty()) return p
        if (f.start !in f.nodes) return listOf("start '${f.start}' is not a node")
        // Reachable from start.
        val seen = HashSet<String>().apply { add(f.start) }
        val todo = ArrayDeque(listOf(f.start))
        while (todo.isNotEmpty()) for (t in f.nodes.getValue(todo.removeFirst()).targets) if (seen.add(t)) todo.add(t)
        f.nodes.keys.filter { it !in seen }.sorted().forEach { p += "node $it: not reachable from start" }
        // An end reachable from every node (walk the edges backwards from the ends).
        val back = HashMap<String, MutableList<String>>()
        for (n in f.nodes.values) for (t in n.targets) back.getOrPut(t) { mutableListOf() } += n.id
        val ok = f.nodes.values.filter { it.type == NodeType.END }.map { it.id }.toHashSet()
        if (ok.isEmpty()) p += "no end node"
        val q = ArrayDeque(ok)
        while (q.isNotEmpty()) for (b in back[q.removeFirst()].orEmpty()) if (ok.add(b)) q.add(b)
        f.nodes.keys.filter { it !in ok && it in seen }.sorted().forEach { p += "node $it: no way from here to an end (trap loop)" }
        return p
    }

    private fun toolProblems(n: Node): List<String> {
        val t = n.tool ?: return listOf("node ${n.id}: tool.kind must be metronome, timer or timestamp")
        return when (t.kind) {
            ToolKind.UNKNOWN -> listOf("node ${n.id}: tool.kind must be metronome, timer or timestamp")
            ToolKind.METRONOME -> buildList {
                if (t.rateBpm <= 0 || t.rateBpm > 300) add("node ${n.id}: metronome needs rate_bpm between 1 and 300")
                if (t.compressions < 0 || t.breaths < 0 || t.cycleSeconds < 0) add("node ${n.id}: metronome counts must be >= 0")
                if (t.breaths > 0 && t.compressions == 0) add("node ${n.id}: breaths without compressions")
            }
            ToolKind.TIMER -> if (t.seconds <= 0) listOf("node ${n.id}: timer needs seconds > 0") else emptyList()
            ToolKind.TIMESTAMP -> if (t.label.isBlank()) listOf("node ${n.id}: timestamp needs a label") else emptyList()
        }
    }
}
