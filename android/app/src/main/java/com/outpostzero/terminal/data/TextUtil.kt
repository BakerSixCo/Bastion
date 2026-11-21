package com.outpostzero.terminal.data

/**
 * Pure-JVM text helpers, kept free of Android imports so they run in plain
 * unit tests without an emulator.
 */
object TextUtil {

    /** Markers emitted by SQLite's snippet() and parsed by the UI. */
    const val MARK_START = '\u0001'
    const val MARK_END = '\u0002'

    /** True if the buffer looks like binary data rather than text. */
    fun isLikelyBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        if (n == 0) return false
        var suspicious = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09) suspicious++
        }
        return suspicious > n / 50
    }

    /**
     * Builds a safe FTS4 MATCH expression from raw user input: each term is
     * quoted (so FTS syntax in the input cannot change the query's meaning)
     * and prefix-matched. Returns null when there are no usable terms.
     *
     * FTS4 only honors the prefix `*` inside the quotes; after the closing
     * quote it is silently ignored and the term matches whole tokens only.
     */
    fun ftsQuery(raw: String): String? {
        val terms = raw.split(Regex("\\s+"))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .take(8)
        if (terms.isEmpty()) return null
        return terms.joinToString(" ") { "\"$it*\"" }
    }

    /** Case-insensitive ranges of term occurrences, for reader highlighting. */
    fun highlightRanges(text: String, rawQuery: String, limit: Int = 500): List<IntRange> {
        val terms = rawQuery.split(Regex("\\s+"))
            .map { it.trim('"', '*', ' ') }
            .filter { it.length >= 2 }
            .distinct()
        if (terms.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val out = ArrayList<IntRange>()
        for (t in terms) {
            val tl = t.lowercase()
            var idx = lower.indexOf(tl)
            while (idx >= 0 && out.size < limit) {
                out.add(idx until idx + tl.length)
                idx = lower.indexOf(tl, idx + tl.length)
            }
        }
        return out.sortedBy { it.first }
    }
}
