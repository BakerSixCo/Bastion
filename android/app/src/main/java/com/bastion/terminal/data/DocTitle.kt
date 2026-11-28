package com.bastion.terminal.data

/**
 * A readable title for a document whose file name says nothing ("Water (6)",
 * as the PDFs inside reference bundles are named). The title is what a
 * citation shows, so it has to tell the reader what the source is. Pure JVM.
 */
object DocTitle {

    private val FILE_LIKE = Regex("^\\S+\\.(pdf|docx?|indd|pub|qxd|p65|pmd|ps|dvi|tex|rtf)$", RegexOption.IGNORE_CASE)
    /** "pioneering book.doc", "Cuta4394.tmp": the name of the file the PDF was made from, not a title. */
    private val ENDS_LIKE_A_FILE = Regex("[.][A-Za-z0-9]{2,4}$")
    private val MADE_BY = Regex("^(Microsoft Word|Microsoft PowerPoint|PowerPoint Presentation|Adobe \\w+|Slide \\d+)\\s*[-–:]?\\s*", RegexOption.IGNORE_CASE)
    private val BOILERPLATE = Regex(
        "distribution|restriction|approved for public release|copyright|all rights reserved|table of contents|^contents$|^cover\\b|" +
            "^page \\d|https?:|www\\.|^for more |^such |^this (publication|document|page)|unclassified|^chapter \\d+$|^preface$|^foreword$",
        RegexOption.IGNORE_CASE,
    )

    /** A title someone wrote into the PDF, if it reads like one. */
    fun fromMetadata(meta: String?): String? {
        val t = MADE_BY.replace(meta?.trim().orEmpty().replace(Regex("\\s+"), " "), "").trim()
        return t.takeIf {
            it.length in 6..110 && it.count(Char::isLetter) >= 5 && !FILE_LIKE.matches(it) &&
                !it.equals("untitled", ignoreCase = true) && !it.equals("unknown", ignoreCase = true) && !ENDS_LIKE_A_FILE.containsMatchIn(it) && (' ' in it || it.none { c -> c == '_' }) && !BOILERPLATE.containsMatchIn(it)
        }
    }

    /** The first line of the text that reads like a heading rather than a notice or half a sentence. */
    fun fromText(text: String): String? =
        text.lineSequence().take(60).map { it.trim().replace(Regex("\\s+"), " ") }.firstOrNull { l ->
            l.length in 8..90 && l.count(Char::isLetter) >= 6 && l.count { it == ' ' } >= 1 &&
                (l[0].isUpperCase() || l[0].isDigit()) && l.last() !in ".,:;" && !BOILERPLATE.containsMatchIn(l)
        }

    /** [fallback]: the file's own name, kept in brackets so the document can still be traced to its file. */
    fun pick(meta: String?, text: String, fallback: String): String =
        (fromMetadata(meta) ?: fromText(text))?.let { "$it ($fallback)" } ?: fallback
}
