package com.bastion.terminal.ai

import com.bastion.terminal.ai.SourceTier.FORUM
import com.bastion.terminal.ai.SourceTier.OFFICIAL
import com.bastion.terminal.ai.SourceTier.OWN
import com.bastion.terminal.ai.SourceTier.REFERENCE
import com.bastion.terminal.ai.SourceTier.UNRATED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/library-plan.md: which sources may be the basis of an AI answer. */
class SourceTierTest {

    @Test fun theKitsReferencesAreClassifiedByFileName() {
        val expected = mapOf(
            "wwwnc.cdc.gov_en_all_2024-11.zim" to OFFICIAL,
            "medlineplus.gov_en_all_2025-01.zim" to OFFICIAL,
            "nhs.uk_en_medicines_2026-09b.zim" to OFFICIAL,
            "irp.fas.org_en_military-medicine_2026-10.zim" to OFFICIAL,
            "wikipedia_en_medicine_nopic_2026-10.zim" to REFERENCE,
            "ifixit_en_all_2025-12.zim" to REFERENCE,
            "appropedia_en_all_maxi_2026-02.zim" to REFERENCE,
            "energypedia_en_all_nopic_2026-06.zim" to REFERENCE,
            "zimgit-post-disaster_en_2024-05.zim" to REFERENCE,
            "outdoors.stackexchange.com_en_all_2026-10.zim" to FORUM,
            "mechanics.stackexchange.com_en_all_2026-10.zim" to FORUM,
            "gardening.stackexchange.com_en_all_2026-10.zim" to FORUM,
            "somebodys-prepper-blog_en_2025.zim" to UNRATED,
        )
        for ((file, tier) in expected) {
            assertEquals(file, tier, SourceTier.forZim("/storage/emulated/0/Android/media/com.bastion.terminal/zim/$file"))
        }
        assertFalse(FORUM.usedForAnswers)
        assertFalse(UNRATED.usedForAnswers)
        assertTrue(OFFICIAL.isTruth && OWN.isTruth && !REFERENCE.isTruth)
    }

    @Test fun libraryDocumentsAreTheOwnersUnlessTheyCameOutOfABundle() {
        assertEquals(OWN, SourceTier.forProvenance("From BASTION's docs folder · PDF text layer extracted · 2026-11-22"))
        assertEquals(OWN, SourceTier.forProvenance("Imported via file picker · 2026-11-21"))
        assertEquals(REFERENCE, SourceTier.forProvenance("From “Post-disaster library” (zimgit-post-disaster_en_2024-05.zim) · PDF"))
        assertEquals(OFFICIAL, SourceTier.forProvenance("From “Military medicine” (irp.fas.org_en_military-medicine_2026-10.zim) · PDF"))
    }

    private val official = OFFICIAL to "CDC: Water treatment\nBoil water for one full minute to make it safe to drink."
    private val wiki = REFERENCE to "WikiMed: Water purification\nBoiling water for one minute kills most pathogens, says the article on boiling."
    private val forum = FORUM to "Outdoors Q&A: boiling\nwater does not even have to boil to be safe to drink"

    @Test fun anOfficialPassageCoveringTheSubjectRemovesReferenceAndForumOnes() {
        val keep = SourcePolicy.preferTruth(listOf(official, wiki, forum), listOf("boil", "water"))
        assertEquals(setOf(0), keep)
    }

    @Test fun aWeakTrustedMentionDoesNotDisplaceAStrongReferencePassage() {
        val sampleNote = OWN to "SAMPLE - Bastion field notes\nWatch for hypothermia on night patrols."
        val wikiArticle = REFERENCE to "WikiMed: Hypothermia — Signs and symptoms\nShivering, confusion and slurred speech are signs of hypothermia."
        val keep = SourcePolicy.preferTruth(listOf(sampleNote, wikiArticle), listOf("hypothermia"), scores = listOf(1.0, 6.0))
        assertEquals(setOf(0, 1), keep)
        val strongNote = SourcePolicy.preferTruth(listOf(sampleNote, wikiArticle), listOf("hypothermia"), scores = listOf(5.0, 6.0))
        assertEquals("a strong trusted match does displace it", setOf(0), strongNote)
    }

    @Test fun referenceIsUsedWhenNoOfficialPassageCoversTheSubject() {
        val offTopicOfficial = OFFICIAL to "CDC: Travel vaccines\nGet vaccinated before you travel."
        val keep = SourcePolicy.preferTruth(listOf(offTopicOfficial, wiki, forum), listOf("boil", "water"))
        assertEquals("forums never, reference as fallback", setOf(0, 1), keep)
    }

    @Test fun theSameTextIsKeptOnceFromTheMostTrustedSource() {
        val text = "Fill a clear plastic bottle with water, shake it, and leave it in full sun for six hours before drinking."
        val keep = SourcePolicy.preferTruth(
            listOf(REFERENCE to "Post-disaster bundle\n$text", OFFICIAL to "CDC: SODIS\n$text", OWN to "My notes\nCarry two bottles of water on the trail."),
            listOf("bottle"), scores = listOf(9.0, 1.0, 5.0),
        )
        assertTrue("the official copy stays even with a lower score", 1 in keep)
        assertFalse("the bundle copy of the same text goes", 0 in keep)
        assertTrue("different text stays", 2 in keep)
    }
}
