package com.bastion.terminal.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removes ONE document from the real library by exact title, for retiring a superseded source on
 * a test device without tapping through the UI. Does nothing (skipped) unless
 * `-e deleteTitle "<exact title>"` is given. Never a wildcard, never more than one title.
 */
@RunWith(AndroidJUnit4::class)
class LibraryAdminTest {
    @Test
    fun deleteOneDocumentByExactTitle() = runBlocking<Unit> {
        val title = InstrumentationRegistry.getArguments().getString("deleteTitle")
        assumeTrue("needs -e deleteTitle <exact title>: skipped", !title.isNullOrBlank())
        val store = LibraryStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val hits = store.listDocs().filter { it.title == title }
        Log.i("LibraryAdmin", "title=$title matches=${hits.size}")
        hits.forEach { store.deleteDoc(it.id) }
        Log.i("LibraryAdmin", "deleted=${hits.size} remaining=${store.listDocs().size}")
    }
}
