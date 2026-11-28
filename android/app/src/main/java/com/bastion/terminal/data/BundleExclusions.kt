package com.bastion.terminal.data

import org.json.JSONArray
import org.json.JSONObject

/** Rules for PDFs in named ZIM bundles. Paths are literal archive paths. */
internal class BundleExclusions private constructor(private val rules: List<Rule>) {
    private enum class Match { TITLE, PATH }
    private data class Rule(val zim: String, val match: Match, val value: String)
    private data class AuditedPdf(val sha256: String, val paths: Set<String>, val previousArchive: String = MEDICINE_ARCHIVE)

    fun matches(zimFileName: String, path: String? = null, title: String? = null): Boolean {
        if (!isZimFileName(zimFileName)) return false
        if (path != null && auditedPathSha(zimFileName, path) != null) return true
        val normalizedTitle = title?.let(::titleKey)
        return rules.any { rule ->
            zimFileName.startsWith(rule.zim) && when (rule.match) {
                Match.PATH -> path == rule.value
                Match.TITLE -> normalizedTitle == rule.value
            }
        }
    }

    fun auditedPathSha(zimFileName: String, path: String): String? {
        if (zimFileName != AUDITED_ARCHIVE) return null
        return rules.asSequence()
            .filter { it.match == Match.TITLE && zimFileName.startsWith(it.zim) }
            .mapNotNull { auditedPdfs[it.value] }
            .firstOrNull { path in it.paths }?.sha256
    }

    fun matchesImportedTitle(zimFileName: String, displayedTitle: String, filename: String, sha256: String? = null): Boolean {
        if (matches(zimFileName, title = displayedTitle)) return true
        // Older rows store a derived display title. A hash of the audited PDF identifies the
        // reviewed document even when the old title was sanitized or the archive is detached.
        if (sha256 != null && rules.any {
                if (it.match != Match.TITLE || !AUDITED_ARCHIVE.startsWith(it.zim)) return@any false
                val pdf = auditedPdfs[it.value] ?: return@any false
                pdf.sha256 == sha256 && (zimFileName == AUDITED_ARCHIVE || zimFileName == pdf.previousArchive)
            }) return true
        if (!filename.endsWith(".pdf", ignoreCase = true)) return false
        val base = filename.dropLast(4)
        if (!NUMBERED_NAME.matches(base)) return false
        // DocTitle.pick adds this exact suffix when a heading supplies the title of a numbered PDF.
        val suffix = " (${base.substringAfterLast('/')})"
        return displayedTitle.endsWith(suffix) && matches(zimFileName, title = displayedTitle.removeSuffix(suffix))
    }

    companion object {
        val EMPTY = BundleExclusions(emptyList())
        private const val AUDITED_ARCHIVE = "zimgit-post-disaster_en_2024-05.zim"
        private const val MEDICINE_ARCHIVE = "zimgit-medicine_en_2024-08.zim"
        // The release's entry names are numbered. The audit used the PDF title/cover.
        // These identities are active only when the corresponding title rule is present.
        private val auditedPdfs = mapOf(
            "under the tongue diagnosis.doc" to AuditedPdf(
                "041b726f6a20278fadd5833a27c57bca78dcc4525f233e97df6c408883ba037c", setOf("files/First Aid and Medicine (9).pdf"),
            ),
            "berkeley, california, usa" to AuditedPdf(
                "bde3204b7343f0fa41763ea614f0916b9b562570d4965d4ed9e9b49f50ce98e2", setOf("files/First Aid and Medicine (2).pdf"),
            ),
            "personal wilderness ems" to AuditedPdf(
                "33501253b638a000f0b04905c1cfa99a9cc59b32d7063a63e40dd027c9f2343f",
                setOf("files/Emergency Preparedness (9).pdf", "files/First Aid and Medicine (8).pdf"),
            ),
            "survival and austere" to AuditedPdf(
                "5a915400e883e4fe8039fe30bb6364e2f585378c591763978f5c2de3cce01abb", setOf("files/First Aid and Medicine (7).pdf"),
            ),
            "uta4394.tmp" to AuditedPdf(
                "6b7a73623792857612b1dba276ed6c77d5a975e1106c561d6fad38ca217bbc62", setOf("files/Knots (6).pdf"),
                previousArchive = "zimgit-knots_en_2024-08.zim",
            ),
            "wound closure manual" to AuditedPdf(
                "5e7e39caddd45df7e6b7a5f505478dcd0f64c1e0946ae7c1e52acd87373eb5b2", setOf("files/First Aid and Medicine (5).pdf"),
            ),
        )
        private val SPACE = Regex("\\s+")
        private val NUMBERED_NAME = Regex(".*\\(\\d+\\)$")
        private val IMPORTED_PDF = Regex(
            """From “.*” \(([^/\\\r\n]+(?i:\.zim))\) · PDF text layer extracted · \d{4}-\d{2}-\d{2} \d{2}:\d{2} · SHA-256 [0-9a-f]{12}…""",
        )

        /** Only provenance written by the bundle-PDF import supplies an archive name. */
        fun importedArchive(provenance: String): String? =
            IMPORTED_PDF.matchEntire(provenance)?.groupValues?.get(1)?.takeIf(::isZimFileName)

        fun parse(json: String): BundleExclusions {
            val entries = JSONArray(json)
            return BundleExclusions((0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                require(entry.length() == 4) { "Each bundle exclusion needs zim, match, value and reason." }
                val zim = entry.requiredString("zim")
                require(isBaseName(zim)) { "The bundle exclusion prefix must be a filename prefix." }
                val match = when (entry.requiredString("match")) {
                    "title" -> Match.TITLE
                    "path" -> Match.PATH
                    else -> throw IllegalArgumentException("A bundle exclusion matches a title or a path.")
                }
                val value = entry.requiredString("value")
                entry.requiredString("reason")
                Rule(zim, match, if (match == Match.TITLE) titleKey(value) else value)
            })
        }

        private fun JSONObject.requiredString(key: String): String =
            (opt(key) as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing or invalid bundle exclusion field: $key.")

        private fun isBaseName(text: String): Boolean =
            text.isNotBlank() && text == text.trim() && text.none { it in "/\\:" || it.isISOControl() }

        private fun isZimFileName(text: String): Boolean =
            text.length > 4 && text.endsWith(".zim", ignoreCase = true) && isBaseName(text)

        private fun titleKey(text: String): String = text.trim().replace(SPACE, " ").lowercase()
    }
}
