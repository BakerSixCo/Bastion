package com.bastion.terminal.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegexLiteralPortabilityTest {
    @Test
    fun regexLiteralsEscapeOrphanClosingDelimiters() {
        val src = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        // These citation patterns predate this check and are outside radio-3b's edit scope.
        // Each exception covers one occurrence of the exact spelling, and only its closing ].
        val legacy = mutableSetOf(
            "ai/CheckedAnswer.kt" to """\[[\d,\s]+]""",
            "ai/CheckedAnswer.kt" to """\s*\[[\d,\s]+]""",
            "ai/Grounding.kt" to """\[(\d{1,3}(?:\s*,\s*\d{1,3})*)]""",
            "kiwix/HtmlText.kt" to """\[(\d{1,3}|[a-z]|citation needed|clarification needed|when\?|who\?|note \d+)]""",
        )
        val problems = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(src).invariantSeparatorsPath.removePrefix("com/bastion/terminal/")
                regexLiterals(file.readText()).flatMap { (line, pattern) ->
                    val closers = orphanClosers(pattern)
                    if (closers == listOf(']') && legacy.remove(path to pattern)) emptySequence()
                    else closers.asSequence().map { "$path:$line: escape the literal '$it' in $pattern" }
                }
            }.toList()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("Remove unused regex exceptions: $legacy", legacy.isEmpty())
    }

    @Test
    fun distinguishesRegexSyntaxFromLiteralDelimiters() {
        assertEquals(listOf('}'), orphanClosers("""\{[0-9]{1,3}\s*}"""))
        assertEquals(listOf(']', ')'), orphanClosers("""\[[0-9]+](key))"""))
        assertEquals(listOf('}'), orphanClosers("""\\}"""))
        for (pattern in listOf(
            """\}\]\)""",
            """(?:[})]|\d{3}|\d{2,5}|\d{2,})""",
            """\p{L}\P{N}\x{41}[]][^]]""",
            """[[a-z]&&[^q]]""",
            """\Q})]\E""",
        )) {
            assertEquals(pattern, emptyList<Char>(), orphanClosers(pattern))
        }
    }

    @Test
    fun scansCodeLiteralsInsteadOfCommentsOrStringText() {
        val source = """
            // Regex("}")
            /* Regex("}") /* Nested comment. */ Regex("]") */
            val message = "Regex(\"}\")"
            val pattern = Regex(
                /* about this pattern */ "\\{[0-9]{1,3}}"
            )
            val dynamic = Regex(")" + fragment)
        """.trimIndent()
        assertEquals(listOf(5 to """\{[0-9]{1,3}}"""), regexLiterals(source).toList())
    }

    // This is a source check for direct constructor literals. Interpolated and composed
    // regexes are left to runtime tests. It does not parse the full regex grammar.
    private fun regexLiterals(source: String): Sequence<Pair<Int, String>> = sequence {
        var i = skipTrivia(source, 0)
        while (i < source.length) {
            if (source[i] == '"' || source[i] == '\'') {
                i = quotedEnd(source, i)
            } else if (source.startsWith("Regex", i) && !identifierPart(source.getOrNull(i - 1))) {
                val open = skipTrivia(source, i + "Regex".length)
                if (source.getOrNull(open) == '(') {
                    val start = skipTrivia(source, open + 1)
                    if (source.getOrNull(start) == '"') {
                        val end = quotedEnd(source, start)
                        val raw = source.startsWith("\"\"\"", start)
                        val width = if (raw) 3 else 1
                        val next = source.getOrNull(skipTrivia(source, end))
                        if ((next == ')' || next == ',') && end >= start + 2 * width) {
                            val pattern = decodeLiteral(source.substring(start + width, end - width), raw)
                            if (pattern != null) {
                                val line = source.take(start).count { it == '\n' } + 1
                                yield(line to pattern)
                            }
                        }
                        i = end
                    } else i = open + 1
                } else i += "Regex".length
            } else i++
            i = skipTrivia(source, i)
        }
    }

    private fun identifierPart(char: Char?) = char != null && (char == '_' || char.isLetterOrDigit())

    private fun skipTrivia(source: String, start: Int): Int {
        var i = start
        while (i < source.length) {
            when {
                source[i].isWhitespace() -> i++
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 }
                source.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> { depth++; i += 2 }
                            source.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                else -> return i
            }
        }
        return i
    }

    private fun quotedEnd(source: String, start: Int): Int {
        val raw = source.startsWith("\"\"\"", start)
        val delimiter = if (raw) "\"\"\"" else source[start].toString()
        var i = start + delimiter.length
        while (i < source.length) {
            when {
                source.startsWith(delimiter, i) -> {
                    i += delimiter.length
                    if (raw) while (source.getOrNull(i) == '"') i++
                    return i
                }
                !raw && source[i] == '\\' -> i += 2
                source[start] == '"' && source.startsWith("\${", i) -> i = templateEnd(source, i + 2)
                else -> i++
            }
        }
        return source.length
    }

    private fun templateEnd(source: String, start: Int): Int {
        var i = skipTrivia(source, start)
        var depth = 1
        while (i < source.length) {
            when (source[i]) {
                '"', '\'' -> i = quotedEnd(source, i)
                '{' -> { depth++; i++ }
                '}' -> if (--depth == 0) return i + 1 else i++
                else -> i++
            }
            i = skipTrivia(source, i)
        }
        return i
    }

    private fun decodeLiteral(value: String, raw: Boolean): String? {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val char = value[i++]
            val next = value.getOrNull(i)
            if (char == '$' && (next == '{' || next == '_' || next?.isLetter() == true)) return null
            if (raw || char != '\\') {
                out.append(char)
                continue
            }
            when (val escaped = value.getOrNull(i++)) {
                '\\', '"', '\'', '$' -> out.append(escaped)
                'b' -> out.append('\b')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'u' -> {
                    if (i + 4 > value.length) return null
                    out.append((value.substring(i, i + 4).toIntOrNull(16) ?: return null).toChar())
                    i += 4
                }
                else -> return null
            }
        }
        return out.toString()
    }

    private val quantifier = Regex("""\{[0-9]+(?:,[0-9]*)?\}""")

    private fun orphanClosers(pattern: String): List<Char> = buildList {
        var i = 0
        var groups = 0
        val classStarts = mutableListOf<Int>()
        while (i < pattern.length) {
            val char = pattern[i]
            if (char == '\\') {
                val next = pattern.getOrNull(i + 1)
                i = when {
                    next == 'Q' -> pattern.indexOf("\\E", i + 2).let { if (it < 0) pattern.length else it + 2 }
                    next != null && next in "pPxN" && pattern.getOrNull(i + 2) == '{' ->
                        pattern.indexOf('}', i + 3).let { if (it < 0) i + 2 else it + 1 }
                    else -> i + 2
                }
                continue
            }
            when {
                char == '[' -> classStarts += i + if (pattern.getOrNull(i + 1) == '^') 2 else 1
                char == ']' -> {
                    if (classStarts.isEmpty()) add(char)
                    // ICU and Java allow a leading ] as a class member, including after ^.
                    else if (i != classStarts.last()) classStarts.removeAt(classStarts.lastIndex)
                }
                classStarts.isNotEmpty() -> Unit
                char == '(' -> groups++
                char == ')' -> if (groups == 0) add(char) else groups--
                char == '{' -> quantifier.matchAt(pattern, i)?.let { i = it.range.last }
                char == '}' -> add(char)
            }
            i++
        }
    }
}
