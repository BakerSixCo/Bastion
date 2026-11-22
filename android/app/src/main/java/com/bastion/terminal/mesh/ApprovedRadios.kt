package com.bastion.terminal.mesh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which radios may get automatic answers (owner decision 2026-11-22:
 * approved radios only). A radio that asks without being approved gets no
 * reply and shows up under "asked, not approved" so the owner can approve it
 * with one tap.
 *
 * Honest limit: BASTION learns who sent a message from the name the
 * Meshtastic app shows. A radio's name can be changed by whoever holds it, so
 * this stops strangers and lost radios that keep their name — it is not
 * cryptographic identity. The private channel key remains the real barrier.
 */
class ApprovedRadios private constructor(app: Context) {
    private val prefs = app.getSharedPreferences("mesh_approved", Context.MODE_PRIVATE)

    private val _approved = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSortedSet().toList())
    val approved: StateFlow<List<String>> = _approved

    /** Radios that asked a question but aren't approved (kept in memory only). */
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = _pending

    fun isApproved(name: String): Boolean = _approved.value.any { it.equals(name.trim(), ignoreCase = true) }

    fun approve(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        save(_approved.value.filterNot { it.equals(n, ignoreCase = true) } + n)
        _pending.value = _pending.value.filterNot { it.equals(n, ignoreCase = true) }
    }

    fun remove(name: String) = save(_approved.value.filterNot { it.equals(name, ignoreCase = true) })

    fun notePending(name: String) {
        val n = name.trim()
        if (n.isEmpty() || isApproved(n) || _pending.value.any { it.equals(n, ignoreCase = true) }) return
        _pending.value = (_pending.value + n).takeLast(10)
    }

    private fun save(list: List<String>) {
        val sorted = list.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        prefs.edit().putStringSet(KEY, sorted.toSet()).apply()
        _approved.value = sorted
    }

    companion object {
        private const val KEY = "names"
        @Volatile private var inst: ApprovedRadios? = null
        fun get(context: Context): ApprovedRadios =
            inst ?: synchronized(this) { inst ?: ApprovedRadios(context.applicationContext).also { inst = it } }
    }
}
