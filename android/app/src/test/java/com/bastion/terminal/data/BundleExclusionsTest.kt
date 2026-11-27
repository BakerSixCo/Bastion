package com.bastion.terminal.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundleExclusionsTest {
    private val firstEdition = "zimgit-test-bundle_en_2024-05.zim"
    private val nextEdition = "zimgit-test-bundle_en_2025-11.zim"
    private val rules = BundleExclusions.parse(
        """
        [
          {"zim":"zimgit-test-bundle","match":"title","value":"Fixture Chapter.doc","reason":"Unvetted fixture"},
          {"zim":"zimgit-test-bundle","match":"path","value":"files/Hidden item.pdf","reason":"Unvetted fixture"}
        ]
        """.trimIndent(),
    )

    @Test
    fun titleMatchesOnlyThatBundleAndTheEntireDisplayTitle() {
        assertTrue(rules.matches(firstEdition, title = "  FIXTURE   CHAPTER.doc  "))
        assertTrue(rules.matches(nextEdition, title = "Fixture Chapter.doc"))
        assertTrue(rules.matches("zimgit-test-bundle.zim", title = "Fixture Chapter.doc"))
        assertFalse(rules.matches("copy-zimgit-test-bundle_en_2024-05.zim", title = "Fixture Chapter.doc"))
        assertFalse(rules.matches("other-bundle.zim", title = "Fixture Chapter.doc"))
        assertFalse(rules.matches(firstEdition, title = "Fixture Chapter"))
        assertFalse(rules.matches(firstEdition, title = "Annotated Fixture Chapter.doc"))
        assertFalse(rules.matches(firstEdition, path = "Fixture Chapter.doc"))
    }

    @Test
    fun pathMatchesOnlyTheLiteralEntryPathAndTheSpecifiedBundle() {
        assertTrue(rules.matches(firstEdition, path = "files/Hidden item.pdf", title = "A different display title"))
        assertFalse(rules.matches(firstEdition, path = "files/hidden item.pdf"))
        assertFalse(rules.matches(firstEdition, path = "files/Hidden item"))
        assertFalse(rules.matches(firstEdition, title = "files/Hidden item.pdf"))
        assertFalse(rules.matches("other-bundle.zim", path = "files/Hidden item.pdf"))
        assertFalse(BundleExclusions.EMPTY.matches(firstEdition, path = "files/Hidden item.pdf"))
    }

    @Test
    fun aMalformedRuleDoesNotBecomeABroaderExclusion() {
        val bad = listOf(
            """{"zim":"zimgit-test-bundle","match":"title","value":"Fixture","reason":"Unvetted"}""",
            """[{"zim":"zimgit-test-bundle","match":"unknown","value":"Fixture","reason":"Unvetted"}]""",
            """[{"zim":"zimgit-test-bundle","match":"title","value":12,"reason":"Unvetted"}]""",
            """[{"zim":"zimgit-test-bundle","match":"title","value":"  ","reason":"Unvetted"}]""",
            """[{"zim":"zimgit-test-bundle","match":"title","value":"Fixture"}]""",
        )
        for (json in bad) assertThrows(Exception::class.java) { BundleExclusions.parse(json) }
    }

    @Test
    fun numberedBundlePdfIsExcludedOnceItsDisplayTitleIsKnown() {
        val path = "files/Section (12).pdf"
        val title = DocTitle.pick(null, "Fixture Chapter.doc", "Section (12)")
        assertEquals("Fixture Chapter.doc (Section (12))", title)
        assertFalse(rules.matches(firstEdition, path = path))
        assertTrue(rules.matchesImportedTitle(firstEdition, title, path))
        assertFalse(rules.matchesImportedTitle(firstEdition, "Fixture Chapter.doc (Another section)", path))
        assertFalse(rules.matchesImportedTitle(firstEdition, title, "files/Another Section (12).pdf"))
        assertFalse(rules.matchesImportedTitle("other-bundle.zim", title, path))
    }

    @Test
    fun exclusionGatesKeepPathsUnreadAndSkipIndexingResolvedTitles() {
        data class Pdf(val path: String, val firstLine: String)
        val offered = listOf(
            Pdf("files/Hidden item.pdf", "Ordinary chapter"),
            Pdf("files/Section (12).pdf", "Fixture Chapter.doc"),
            Pdf("files/Section (13).pdf", "Ordinary chapter"),
        )
        val read = mutableListOf<String>()
        val indexed = mutableListOf<String>()
        for ((path, firstLine) in offered) {
            if (rules.matches(firstEdition, path = path)) continue
            read += path
            val title = DocTitle.pick(null, firstLine, path.substringAfterLast('/').removeSuffix(".pdf"))
            if (rules.matchesImportedTitle(firstEdition, title, path)) continue
            indexed += path
        }
        assertEquals(listOf("files/Section (12).pdf", "files/Section (13).pdf"), read)
        assertEquals(listOf("files/Section (13).pdf"), indexed)
    }

    @Test
    fun storedExclusionSelectsOnlyTheTargetBundleRowForRemoval() {
        val filename = "files/Section (12).pdf"
        val title = DocTitle.pick(null, "Fixture Chapter.doc", "Section (12)")
        val tail = " · PDF text layer extracted · 2026-11-26 12:00 · SHA-256 0123456789ab…"
        val rows = listOf(
            1 to "From “Fixture reference bundle” ($firstEdition)$tail",
            2 to "From “Other reference bundle” (other-bundle.zim)$tail",
            3 to "From BASTION's docs folder$tail",
            4 to "From BASTION's reference docs folder$tail",
            5 to "Imported via Android file picker$tail",
        )
        val removed = rows.filter { (_, provenance) ->
            BundleExclusions.importedArchive(provenance)?.let {
                rules.matchesImportedTitle(it, title, filename)
            } == true
        }.map { it.first }
        assertEquals(listOf(1), removed)
        assertEquals(firstEdition, BundleExclusions.importedArchive(rows[0].second))
        for ((_, provenance) in rows.drop(2)) {
            assertEquals(null, BundleExclusions.importedArchive(provenance))
            assertEquals(null, BundleExclusions.importedArchive("$provenance ($firstEdition)"))
        }
    }

    @Test
    fun shippedAssetListsExactlyTheSixAuditedPostDisasterTitles() {
        val asset = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "android/app/src/main/assets/library/exclude.json") }.first { it.isFile }
        val json = asset.readText()
        val expected = setOf(
            "under the tongue diagnosis.doc", "Berkeley, California, USA", "Personal Wilderness EMS",
            "Survival and Austere", "uta4394.tmp", "Wound Closure Manual",
        )
        val entries = JSONArray(json)
        assertEquals(expected.size, entries.length())
        assertEquals(expected, (0 until entries.length()).map { i ->
            val rule = entries.getJSONObject(i)
            assertEquals("zimgit-post-disaster", rule.getString("zim"))
            assertEquals("title", rule.getString("match"))
            assertEquals("origin unknown (owner/Claude audit 2026-11-26)", rule.getString("reason"))
            rule.getString("value")
        }.toSet())

        val shipped = BundleExclusions.parse(json)
        for (title in expected) assertTrue(shipped.matches("zimgit-post-disaster_en_2024-05.zim", title = title))
        assertFalse(shipped.matches("zimgit-post-disaster_en_2024-05.zim", title = "Unrelated fixture PDF"))
    }

    @Test
    fun auditedDuplicatePathsAndLegacyHashIdentifyOnePdfInTheReviewedEdition() {
        val rule = BundleExclusions.parse(
            """[{"zim":"zimgit-post-disaster","match":"title","value":"Personal Wilderness EMS","reason":"Audit"}]""",
        )
        val reviewed = "zimgit-post-disaster_en_2024-05.zim"
        val updated = "zimgit-post-disaster_en_2025-11.zim"
        val filename = "files/Emergency Preparedness (9).pdf"
        val duplicate = "files/First Aid and Medicine (8).pdf"
        val hash = "33501253b638a000f0b04905c1cfa99a9cc59b32d7063a63e40dd027c9f2343f"
        assertEquals(hash, rule.auditedPathSha(reviewed, filename))
        assertEquals(hash, rule.auditedPathSha(reviewed, duplicate))
        assertTrue(rule.matches(reviewed, path = duplicate))
        assertTrue(rule.matchesImportedTitle(reviewed, "Different display title", filename, hash))
        assertTrue(rule.matchesImportedTitle("zimgit-medicine_en_2024-08.zim", "Different display title", filename, hash))
        assertFalse(rule.matchesImportedTitle("zimgit-knots_en_2024-08.zim", "Different display title", filename, hash))
        assertFalse(rule.matchesImportedTitle(reviewed, "Different display title", filename, "0".repeat(64)))
        assertFalse(rule.matchesImportedTitle(updated, "Different display title", filename, hash))
        assertFalse(rule.matches(updated, path = duplicate))
        assertFalse(BundleExclusions.EMPTY.matches(reviewed, path = duplicate))
    }
}
