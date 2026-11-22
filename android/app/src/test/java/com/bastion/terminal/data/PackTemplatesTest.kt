package com.bastion.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTemplatesTest {
    @Test
    fun everyTemplateHasHeadingsTheChunkerCanUse() {
        for (k in PackKind.entries) {
            val t = k.starter()
            assertTrue("${k.name} has headings", t.lines().any { it.startsWith("## ") })
            assertTrue("${k.name} is not indented", t.lines().none { it.startsWith(" ") })
            assertTrue(Chunker.chunk(t.replace(":\n", ": x\n")).all { it.heading != null })
        }
        assertEquals(PackKind.HUNT, PackKind.of("HUNT"))
        assertEquals(PackKind.REFERENCE, PackKind.of("something old"))
    }
}
