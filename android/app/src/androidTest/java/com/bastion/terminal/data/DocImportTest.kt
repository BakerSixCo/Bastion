package com.bastion.terminal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF (real PdfBox-Android on device) and EPUB import, end to end: imported
 * through the same path as the picker, readable in the reader, and found by
 * Ask's retrieval. Isolated store; fixtures are invented test data.
 */
@RunWith(AndroidJUnit4::class)
class DocImportTest {

    @Test
    fun pdfAndEpubAreImportedReadAndRetrieved() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val store = LibraryStore.isolated(ctx, "doc-import")
        val pdf = store.importBytes(assets.open("qa/field-pdf.pdf").use { it.readBytes() }, "field-pdf.pdf", "test")
        val epub = store.importBytes(assets.open("qa/guide.epub").use { it.readBytes() }, "guide.epub", "test")
        assertTrue("pdf: $pdf", pdf is ImportResult.Ok)
        assertTrue("epub: $epub", epub is ImportResult.Ok)
        pdf as ImportResult.Ok; epub as ImportResult.Ok
        assertEquals("field-pdf", pdf.doc.title)
        assertTrue(pdf.doc.provenance.contains("PDF text layer extracted"))
        assertTrue(store.docText(pdf.doc.id)!!.contains("heliograph three times every minute"))
        assertTrue(store.docText(epub.doc.id)!!.contains("## Knots"))

        val p1 = store.retrieve("How often should I flash the heliograph")
        assertTrue(p1.any { it.docId == pdf.doc.id && it.plainText.contains("three times every minute") })
        val p2 = store.retrieve("Which knot makes a fixed loop")
        assertTrue(p2.any { it.docId == epub.doc.id && it.plainText.contains("bowline") && it.heading == "Knots" })
    }
}
