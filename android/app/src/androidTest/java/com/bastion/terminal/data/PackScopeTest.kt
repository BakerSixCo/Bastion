package com.bastion.terminal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Packs decide what may be answered: Ask uses packs switched on for Ask; a
 * question arriving over the mesh may only use packs switched on for the
 * mesh — and the general library is off for the mesh by default.
 */
@RunWith(AndroidJUnit4::class)
class PackScopeTest {
    @Test
    fun meshOnlySeesPacksTheOwnerAllowed() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore.isolated(ctx, "pack-scope")
        store.setGeneral(inAsk = true, meshOk = false)
        val hunt = store.createPack("Elk camp", PackKind.HUNT.name)
        val contacts = store.createPack("Family contacts", PackKind.CONTACTS.name)
        store.saveNote("Hunting plan", "## Camp and rally points\nRally point if separated: the old sawmill gate.\n", hunt)
        store.saveNote("Contacts", "## Dana Okafor\nAddress: 14 Larkspur Lane\nPhone: 555-0142\n", contacts)
        store.saveNote("Loose note", "## Cache\nThe spare fuel cache is behind the green shed.\n", null)

        fun texts(q: String, s: Scope) = runBlocking { store.retrieve(q, s).joinToString("\n") { it.plainText } }

        // Ask (default switches): everything is usable.
        assertTrue(texts("where is the rally point if separated", Scope.ASK).contains("sawmill gate"))
        assertTrue(texts("what is Dana Okafor's address", Scope.ASK).contains("Larkspur"))
        assertTrue(texts("where is the spare fuel cache", Scope.ASK).contains("green shed"))

        // Mesh, nothing allowed yet: nothing is answerable.
        assertEquals("", texts("where is the rally point if separated", Scope.MESH))
        assertEquals("", texts("what is Dana Okafor's address", Scope.MESH))
        assertEquals("", texts("where is the spare fuel cache", Scope.MESH))

        // Allow only the hunting pack over the mesh.
        store.setPack(hunt, meshOk = true)
        assertTrue(texts("where is the rally point if separated", Scope.MESH).contains("sawmill gate"))
        assertEquals("", texts("what is Dana Okafor's address", Scope.MESH))
        assertEquals("", texts("where is the spare fuel cache", Scope.MESH))

        // Switching a pack off for Ask hides it there too.
        store.setPack(contacts, inAsk = false)
        assertEquals("", texts("what is Dana Okafor's address", Scope.ASK))

        // Editing a note replaces it; deleting a pack removes its documents.
        val old = store.listDocs().first { it.title == "Hunting plan" }
        store.saveNote("Hunting plan", "## Camp and rally points\nRally point if separated: the north water tank.\n", hunt, old.id)
        val after = texts("where is the rally point if separated", Scope.ASK)
        assertTrue(after.contains("north water tank") && !after.contains("sawmill"))
        store.deletePack(hunt)
        assertEquals("", texts("where is the rally point if separated", Scope.ALL))
        assertEquals(2, store.listDocs().size)
    }
}
