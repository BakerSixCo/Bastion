package com.bastion.terminal.kiwix

/**
 * Article HTML (Kiwix/mwoffliner Wikipedia) → plain text with Markdown-style
 * headings, so the shared Chunker can split it into sectioned passages.
 * Pure JVM, no HTML library: regex passes in a fixed order.
 *
 * Removes: scripts/styles, footnote markers (`<sup class="reference">`, "[12]",
 * "[citation needed]" — they would clash with BASTION's own [n] citations),
 * and trailing reference sections (References, External links, …).
 */
object HtmlText {

    private val DROP_SECTIONS = setOf(
        "references", "external links", "see also", "further reading", "notes",
        "bibliography", "sources", "citations", "footnotes",
    )

    fun extract(html: String): String {
        var s = html
        // Content we never want.
        s = s.replace(Regex("(?is)<head\\b.*?</head>"), " ") // <title> would duplicate the heading
        s = s.replace(Regex("(?is)<(script|style|noscript|template)\\b.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?is)<sup\\b[^>]*class=\"[^\"]*reference[^\"]*\"[^>]*>.*?</sup>"), "")
        s = s.replace(Regex("(?is)<(figure|figcaption)\\b.*?</\\1>"), " ")
        // Structure → newlines / Markdown headings.
        s = s.replace(Regex("(?is)<h([1-4])\\b[^>]*>(.*?)</h\\1>")) { m ->
            "\n\n" + "#".repeat(m.groupValues[1].toInt().coerceAtLeast(2)) + " " +
                stripTags(m.groupValues[2]).trim() + "\n\n"
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
        s = s.replace(Regex("(?i)</(p|div|section|ul|ol|table|tr|blockquote|details|dl)>"), "\n\n")
        s = s.replace(Regex("(?i)</(td|th|dd|dt)>"), " ")
        s = stripTags(s)
        s = decodeEntities(s)
        // Wiki footnote markers left as plain text.
        s = s.replace(Regex("\\[(\\d{1,3}|[a-z]|citation needed|clarification needed|when\\?|who\\?|note \\d+)]", RegexOption.IGNORE_CASE), "")
        // Whitespace normalisation, keeping paragraph breaks.
        s = s.replace(' ', ' ')
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return dropTrailingSections(s.trim())
    }

    /**
     * The target of an HTML-level redirect page (`<meta http-equiv="refresh"
     * content="0;url=Pneumothorax">`), as a path relative to the page, or null
     * for a normal page. Percent-decoded, without "./" or an anchor.
     */
    fun redirectTarget(html: String): String? {
        val m = Regex(
            "(?is)<meta\\b[^>]*http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"'][^\"'>]*?url\\s*=\\s*['\"]?([^\"'>]+)",
        ).find(html) ?: return null
        var t = m.groupValues[1].trim().substringBefore('#')
        while (t.startsWith("./")) t = t.drop(2)
        while (t.startsWith("../")) t = t.drop(3)
        return percentDecode(t).takeIf { it.isNotBlank() && !it.contains("://") }
    }

    /** "%C3%A9" → "é". Done by hand: no networking classes outside link/TakClient (NetworkGuardTest). */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { out.write(hex); i += 3 } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun stripTags(s: String) = s.replace(Regex("(?s)<[^>]+>"), "")

    /** Removes "## References"-style sections (to the next heading of the same or higher level). */
    private fun dropTrailingSections(text: String): String {
        val lines = text.split('\n')
        val out = StringBuilder()
        var skipLevel = 0
        for (line in lines) {
            val h = Regex("^(#{1,6}) (.+)$").find(line)
            if (h != null) {
                val level = h.groupValues[1].length
                if (skipLevel in 1..level) skipLevel = 0
                if (skipLevel == 0 && h.groupValues[2].trim().lowercase() in DROP_SECTIONS) {
                    skipLevel = level
                    continue
                }
            }
            if (skipLevel == 0) out.append(line).append('\n')
        }
        return out.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "deg" to "°", "times" to "×",
        "minus" to "−", "plusmn" to "±", "micro" to "µ", "le" to "≤", "ge" to "≥",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "middot" to "·",
    )

    fun decodeEntities(s: String): String =
        Regex("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) }
                else -> NAMED[e]
            } ?: m.value
        }

    private fun cp(code: Int): String? =
        if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null
}
