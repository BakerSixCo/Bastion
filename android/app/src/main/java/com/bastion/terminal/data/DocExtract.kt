package com.bastion.terminal.data

import com.bastion.terminal.kiwix.HtmlText
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Turns an imported file's bytes into indexable text. The original bytes are
 * always preserved separately; this only decides *what text* gets indexed.
 *
 * - Plain text / Markdown: as-is (binary rejected).
 * - EPUB: chapters in spine (reading) order, XHTML → text with headings via
 *   [HtmlText]. Pure JVM (unit-tested).
 * - PDF: the text layer, via the [pdf] extractor supplied by the caller
 *   (PdfBox-Android on device). Scanned PDFs without a text layer are
 *   rejected honestly — no OCR.
 */
object DocExtract {

    enum class Kind { TEXT, EPUB, PDF }

    sealed class Result {
        data class Ok(val text: String, val kind: Kind) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun kindOf(bytes: ByteArray, name: String): Kind {
        val lower = name.lowercase()
        return when {
            startsWith(bytes, "%PDF") -> Kind.PDF
            startsWith(bytes, "PK") && (lower.endsWith(".epub") || isEpubZip(bytes)) -> Kind.EPUB
            else -> Kind.TEXT
        }
    }

    fun extract(bytes: ByteArray, name: String, pdf: (ByteArray) -> String): Result =
        when (kindOf(bytes, name)) {
            Kind.TEXT -> if (TextUtil.isLikelyBinary(bytes)) {
                Result.Rejected(
                    "This looks like binary data. BASTION imports plain text, Markdown, " +
                        "PDF (with a text layer) and EPUB."
                )
            } else Result.Ok(String(bytes, Charsets.UTF_8), Kind.TEXT)
            Kind.EPUB -> runCatching { epubText(bytes) }.fold(
                onSuccess = { t ->
                    if (t.isBlank()) Result.Rejected("This EPUB has no readable text (DRM-protected or image-only?).")
                    else Result.Ok(t, Kind.EPUB)
                },
                onFailure = { Result.Rejected("Could not read this EPUB: ${it.message ?: it.javaClass.simpleName}") },
            )
            Kind.PDF -> runCatching { pdf(bytes) }.fold(
                onSuccess = { t ->
                    // Fewer than ~1 real character per 2 KB: a scan or a picture-only PDF.
                    if (t.count { it.isLetterOrDigit() } < maxOf(20, bytes.size / 2000)) {
                        Result.Rejected(
                            "This PDF has no usable text layer (scanned pages or images). " +
                                "BASTION doesn't do OCR."
                        )
                    } else Result.Ok(normalizePdf(t), Kind.PDF)
                },
                onFailure = {
                    val msg = it.message.orEmpty()
                    if (it.javaClass.simpleName.contains("Password") || msg.contains("password", true) || msg.contains("encrypt", true))
                        Result.Rejected("This PDF is password-protected or encrypted.")
                    else Result.Rejected("Could not read this PDF: ${msg.ifBlank { it.javaClass.simpleName }}")
                },
            )
        }

    private fun startsWith(b: ByteArray, magic: String) =
        b.size >= magic.length && magic.indices.all { b[it] == magic[it].code.toByte() }

    private fun isEpubZip(bytes: ByteArray): Boolean =
        runCatching { unzip(bytes, maxEntries = 4)["mimetype"]?.let { String(it).trim() == "application/epub+zip" } }
            .getOrNull() == true

    /** Entries of a zip held in memory. Bounded: entry count and total inflated size. */
    private fun unzip(bytes: ByteArray, maxEntries: Int = 5000, maxTotal: Long = 200_000_000): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (out.size >= maxEntries) break
                if (e.isDirectory) continue
                val data = z.readBytes()
                total += data.size
                if (total > maxTotal) throw IllegalStateException("EPUB expands to more than ${maxTotal / 1_000_000} MB")
                out[e.name] = data
            }
        }
        return out
    }

    /** EPUB 2/3: container.xml → OPF → manifest + spine → XHTML chapters in order. */
    fun epubText(bytes: ByteArray): String {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) }
            ?: throw IllegalStateException("missing META-INF/container.xml")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: throw IllegalStateException("no package document")
        val opf = files[opfPath]?.let { String(it, Charsets.UTF_8) } ?: throw IllegalStateException("missing $opfPath")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val manifest = HashMap<String, String>()
        for (m in Regex("(?s)<item\\b[^>]*>").findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            manifest[id] = href
        }
        val spine = Regex("(?s)<itemref\\b[^>]*>").findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()
        val title = Regex("(?s)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.trim()

        val sb = StringBuilder()
        if (!title.isNullOrBlank()) sb.append("# ").append(title).append("\n\n")
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            val path = normalize(base + decodeHref(href))
            val html = files[path] ?: continue
            val text = HtmlText.extract(String(html, Charsets.UTF_8))
            if (text.isNotBlank()) sb.append(text).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: Regex("\\b$name\\s*=\\s*'([^']*)'").find(tag)?.groupValues?.get(1)

    private fun decodeHref(h: String) = h.substringBefore('#').replace("%20", " ")

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (s in p.split('/')) when (s) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(s)
        }
        return parts.joinToString("/")
    }

    /** PDF text: join hyphenated line breaks, keep blank lines as paragraph breaks. */
    fun normalizePdf(t: String): String =
        t.replace("\r", "")
            .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
}
