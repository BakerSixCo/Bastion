package com.bastion.terminal.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshBridgeTest {

    @Test
    fun fit_shortTextIsCleanedButNotShortened() {
        val (t, cut) = MeshBridge.fit("Rally at the north water tower [1].\n\n  Bring water [2, 3].")
        assertEquals("Rally at the north water tower.\nBring water.", t)
        assertFalse(cut)
    }

    @Test
    fun fit_leavesOverLimitTextForManualEditing() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val (t, cut) = MeshBridge.fit(long)
        assertEquals(long, t)
        assertFalse(cut)
        assertTrue(MeshBridge.byteLength(t) > MeshBridge.MAX_BYTES)
    }

    @Test
    fun fit_preservesOverLimitMultibyteText() {
        val emoji = "🚑".repeat(100) // 4 bytes each
        val (t, cut) = MeshBridge.fit(emoji)
        assertEquals(emoji, t)
        assertEquals(400, MeshBridge.byteLength(t))
        assertFalse(cut)
    }

    @Test
    fun navigation_sendsWholeSummaryAndLegsInOrder() {
        val parts = MeshBridge.splitForNavigation("Mock route.", listOf("1. Mock leg A.", "2. Mock leg B."))
        assertEquals(listOf("NAV 2/2: Mock route. 1. Mock leg A. 2. Mock leg B."), parts)
    }

    @Test
    fun navigation_accountsForPrefixAtExactByteLimit() {
        val summary = "é".repeat(95) + "."
        val parts = MeshBridge.splitForNavigation(summary, emptyList())
        assertEquals(listOf("NAV 2/2: $summary"), parts)
        assertEquals(MeshBridge.MAX_BYTES, MeshBridge.byteLength(parts.single()))
    }

    @Test
    fun navigation_reservesSpaceForOmissionNoticeWithinThreeParts() {
        val legs = (1..4).map { "$it. " + "é".repeat(86) + "." }
        val parts = MeshBridge.splitForNavigation("Mock route.", legs)
        assertEquals(
            listOf(
                "NAV 2/4: Mock route. ${legs[0]}",
                "NAV 3/4: ${legs[1]}",
                "NAV 4/4: +2 more legs, too long for radio.",
            ),
            parts,
        )
        assertTrue(parts.all { MeshBridge.byteLength(it) <= MeshBridge.MAX_BYTES })
    }

    @Test
    fun navigation_omitsAnOversizedLegAndAllFollowingLegs() {
        val longLeg = "1. Mock leg A. " + "é".repeat(100)
        val parts = MeshBridge.splitForNavigation("Mock route.", listOf(longLeg, "2. Mock leg B."))
        assertEquals(listOf("NAV 2/2: Mock route. +2 more legs, too long for radio."), parts)
    }

    @Test
    fun navigation_usesABoundedNoticeForAnOversizedSummary() {
        val longSummary = "Mock route. " + "é".repeat(100)
        val parts = MeshBridge.splitForNavigation(longSummary, listOf("1. Mock leg A."))
        assertEquals(listOf("NAV 2/2: Route summary too long for radio. +1 more legs, too long for radio."), parts)
        assertTrue(MeshBridge.byteLength(parts.single()) <= MeshBridge.MAX_BYTES)
    }
}
