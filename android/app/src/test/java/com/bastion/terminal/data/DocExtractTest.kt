package com.bastion.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocExtractTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    private fun epub() = zip(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<package><metadata><dc:title>Field Guide</dc:title></metadata>
            <manifest><item id="c2" href="text/ch2.xhtml"/><item id="c1" href="text/ch1.xhtml"/></manifest>
            <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
        "OEBPS/text/ch1.xhtml" to "<html><body><h2>Water</h2><p>Boil for one minute.</p></body></html>",
        "OEBPS/text/ch2.xhtml" to "<html><body><h2>Fire</h2><p>Clear a ring of ground.</p></body></html>",
    )

    private val noPdf: (ByteArray) -> String = { error("not a pdf") }

    @Test
    fun epub_chaptersInSpineOrderWithHeadings() {
        val r = DocExtract.extract(epub(), "guide.epub", noPdf) as DocExtract.Result.Ok
        assertEquals(DocExtract.Kind.EPUB, r.kind)
        assertTrue(r.text.startsWith("# Field Guide"))
        assertTrue(r.text.indexOf("Boil for one minute") < r.text.indexOf("Clear a ring"))
        assertTrue(r.text.contains("## Water") && r.text.contains("## Fire"))
        // Detected by content even without the .epub extension.
        assertEquals(DocExtract.Kind.EPUB, DocExtract.kindOf(epub(), "download.bin"))
    }

    @Test
    fun pdf_textLayerUsedScansAndPasswordsRejected() {
        val pdf = "%PDF-1.7 fake".toByteArray()
        val ok = DocExtract.extract(pdf, "m.pdf") { "Tourni-\nquets are harmful. Keep the limb still and the person calm." }
        assertTrue(ok is DocExtract.Result.Ok && ok.text.startsWith("Tourniquets are harmful"))
        val scan = DocExtract.extract(pdf, "scan.pdf") { "  \n\n " }
        assertTrue((scan as DocExtract.Result.Rejected).reason.contains("no usable text layer"))
        val locked = DocExtract.extract(pdf, "x.pdf") { throw RuntimeException("Cannot decrypt PDF, the password is incorrect") }
        assertTrue((locked as DocExtract.Result.Rejected).reason.contains("password"))
    }

    @Test
    fun text_passesAndBinaryRejectedWithSupportedFormatsListed() {
        assertTrue(DocExtract.extract("hello".toByteArray(), "a.txt", noPdf) is DocExtract.Result.Ok)
        val bin = DocExtract.extract(byteArrayOf(1, 0, 2, 0), "a.bin", noPdf) as DocExtract.Result.Rejected
        assertTrue(bin.reason.contains("PDF") && bin.reason.contains("EPUB"))
        // A zip that isn't an EPUB is binary, not an EPUB.
        assertEquals(DocExtract.Kind.TEXT, DocExtract.kindOf(zip("a.txt" to "x"), "a.zip"))
    }
}
