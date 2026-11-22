package com.bastion.terminal.mesh

import com.bastion.terminal.mesh.MeshAutoResponder.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAutoResponderTest {
    /** Standing rule: only a leading symbol is a command; everything else is chat. */
    @Test
    fun commandIn_onlyLeadingSymbolsAreCommands() {
        assertEquals(Command.Ask("how do I treat a burn"), MeshAutoResponder.commandIn("?how do I treat a burn"))
        assertEquals(Command.Ask("how do I treat a burn"), MeshAutoResponder.commandIn("  ? how do I treat a burn? "))
        assertEquals(Command.Locate("base"), MeshAutoResponder.commandIn("@base"))
        assertEquals(Command.Locate("Sim B"), MeshAutoResponder.commandIn("@ Sim B"))
        // Ordinary chat is never a command — not even a well-formed question, and not a word trigger.
        assertNull(MeshAutoResponder.commandIn("How do I deal with a sucking chest wound"))
        assertNull(MeshAutoResponder.commandIn("Where are you?"))
        assertNull(MeshAutoResponder.commandIn("bastion, symptoms of hypothermia"))
        assertNull(MeshAutoResponder.commandIn("Need water at checkpoint 2"))
        // Empty triggers and BASTION's own replies are ignored.
        assertNull(MeshAutoResponder.commandIn("?"))
        assertNull(MeshAutoResponder.commandIn("@"))
        assertNull(MeshAutoResponder.commandIn("AI: cool the burn with running water"))
        assertNull(MeshAutoResponder.commandIn("AI 1/2: 1. Cover the wound."))
        assertNull(MeshAutoResponder.commandIn("NAV: BASTION: 3.1 km at 183 deg S"))
    }

    @Test
    fun isPublicChannel_defaultPresetNamesArePublic() {
        assertTrue(MeshAutoResponder.isPublicChannel("LongFast"))
        assertTrue(MeshAutoResponder.isPublicChannel(" mediumslow "))
        assertTrue(MeshAutoResponder.isPublicChannel(""))
        assertFalse(MeshAutoResponder.isPublicChannel("Bastion"))
        assertFalse(MeshAutoResponder.isPublicChannel("Team Kestrel"))
    }
}
