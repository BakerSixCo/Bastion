package com.bastion.terminal.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshAutoResponderTest {
    @Test
    fun questionIn_onlyTriggeredMessagesAreQuestions() {
        assertEquals("how do I treat a burn", MeshAutoResponder.questionIn("?how do I treat a burn"))
        assertEquals("how do I treat a burn", MeshAutoResponder.questionIn("  ? how do I treat a burn "))
        assertEquals("symptoms of hypothermia", MeshAutoResponder.questionIn("Bastion, symptoms of hypothermia"))
        assertEquals("symptoms of hypothermia", MeshAutoResponder.questionIn("bastion: symptoms of hypothermia"))
        // Ordinary chat, even when it is a question, is never auto-answered.
        assertNull(MeshAutoResponder.questionIn("Where are you?"))
        assertNull(MeshAutoResponder.questionIn("Need water at checkpoint 2"))
        // Empty triggers and BASTION's own replies are ignored.
        assertNull(MeshAutoResponder.questionIn("?"))
        assertNull(MeshAutoResponder.questionIn("AI: cool the burn with running water"))
    }

    @Test
    fun isPublicChannel_defaultPresetNamesArePublic() {
        org.junit.Assert.assertTrue(MeshAutoResponder.isPublicChannel("LongFast"))
        org.junit.Assert.assertTrue(MeshAutoResponder.isPublicChannel(" mediumslow "))
        org.junit.Assert.assertTrue(MeshAutoResponder.isPublicChannel(""))
        org.junit.Assert.assertFalse(MeshAutoResponder.isPublicChannel("BASTION"))
        org.junit.Assert.assertFalse(MeshAutoResponder.isPublicChannel("Team Kestrel"))
    }
}
