package com.bastion.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocTitleTest {

    @Test
    fun metadataTitle_isUsedWhenItReadsLikeATitle() {
        assertEquals("Emergency Water Treatment", DocTitle.fromMetadata("Microsoft Word - Emergency Water Treatment"))
        assertEquals("Survival, Evasion and Recovery", DocTitle.fromMetadata("  Survival,  Evasion and Recovery "))
        assertNull(DocTitle.fromMetadata("fm21_76.pdf"))
        assertNull(DocTitle.fromMetadata("untitled"))
        assertNull(DocTitle.fromMetadata("Unknown"))
        assertNull(DocTitle.fromMetadata("pioneering book.doc"))
        assertNull(DocTitle.fromMetadata("Cuta4394.tmp"))
        assertNull(DocTitle.fromMetadata(""))
        assertNull(DocTitle.fromMetadata(null))
    }

    @Test
    fun textTitle_skipsNoticesAndHalfSentences() {
        val text = "DISTRIBUTION RESTRICTION: Approved for public release.\nsuch a situation.\n\nFIRST AID FOR SOLDIERS\nChapter 1"
        assertEquals("FIRST AID FOR SOLDIERS", DocTitle.fromText(text))
        assertNull(DocTitle.fromText("12\n\nand so on.\n"))
    }

    @Test
    fun pick_prefersMetadataThenTextThenTheFileName() {
        assertEquals("Knots for Climbers (Knots (4))", DocTitle.pick("Knots for Climbers", "Whatever comes first", "Knots (4)"))
        assertEquals("Working with Ropes (Knots (4))", DocTitle.pick("scan0004.pdf", "Working with Ropes\nmore", "Knots (4)"))
        assertEquals("Knots (4)", DocTitle.pick(null, "1\n2\n", "Knots (4)"))
    }
}
