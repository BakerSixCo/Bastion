package com.bastion.terminal.link

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.bastion.terminal.data.TextUtil
import java.util.UUID

/** Where the connection to the TAK picture stands, in plain words for the UI. */
data class LinkState(val state: String, val detail: String = "") {
    val connected: Boolean get() = state == "connected"
}

/**
 * BASTION's connection to the Meshtastic app's **local TAK server** on this
 * phone (127.0.0.1, mutual TLS): unit positions and TAK chat in, TAK chat out.
 *
 * This is BASTION's **only** use of the network permission (owner decision
 * 2026-11-22: one app instead of a separate "BASTION Link" bridge app).
 * Android requires INTERNET even for a connection inside the phone. The
 * socket is opened to a literal 127.0.0.1 and nowhere else ([TakClient],
 * [TakConfig.requireLoopback]); `NetworkGuardTest` fails the build if network
 * code appears outside this package. Received XML is parsed as data ([Cot]).
 */
class LinkClient private constructor(private val app: Context) {

    private val _state = MutableStateFlow(LinkState("idle"))
    val state: StateFlow<LinkState> = _state

    private val _contacts = MutableStateFlow<List<TakContact>>(emptyList())
    val contacts: StateFlow<List<TakContact>> = _contacts

    private val _chats = MutableStateFlow<List<TakChat>>(emptyList())
    val chats: StateFlow<List<TakChat>> = _chats

    private val main = Handler(Looper.getMainLooper())
    private var tak: TakClient? = null

    fun isSetUp(): Boolean = TakConfig.load(app) != null

    /** Connects if a TAK data package has been imported. Safe to call repeatedly. */
    fun start() {
        if (tak != null) return
        val cfg = TakConfig.load(app)
        if (cfg == null) {
            _state.value = LinkState("not_set_up")
            return
        }
        tak = TakClient(
            cfg,
            onState = { s, d -> main.post { if (tak != null) _state.value = LinkState(s, d) } },
            onEvent = { xml -> main.post { onEvent(xml) } },
        ).also { it.start() }
    }

    fun stop() {
        tak?.stop()
        tak = null
        _state.value = LinkState(if (isSetUp()) "idle" else "not_set_up")
    }

    /** Imports the .zip exported by Meshtastic ("Export TAK Data Package") and reconnects. */
    suspend fun importPackage(uri: Uri): String = withContext(Dispatchers.IO) {
        val result = runCatching {
            val bytes = app.contentResolver.openInputStream(uri)?.use { TextUtil.readBounded(it, 2_000_000) }
                ?: return@runCatching null
            TakConfig.import(app, bytes.inputStream()).also { msg ->
                // Keep the package so an ATAK-ready copy can be saved later.
                if (msg.startsWith("Imported")) java.io.File(java.io.File(app.filesDir, "tak"), ORIGINAL).writeBytes(bytes)
            }
        }.getOrNull() ?: "Could not open the selected file (or it is too large to be a TAK data package)."
        withContext(Dispatchers.Main) { stop(); start() }
        result
    }

    fun canExportForAtak(): Boolean = java.io.File(java.io.File(app.filesDir, "tak"), ORIGINAL).isFile

    /** Saves an ATAK-importable copy of the imported package to [uri] (see [AtakPackage]). */
    suspend fun exportForAtak(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            val src = java.io.File(java.io.File(app.filesDir, "tak"), ORIGINAL)
            app.contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { AtakPackage.repack(it, out) } }
            "Saved. In ATAK: Import → Choose App → pick this file. ATAK then connects to Meshtastic by itself."
        }.getOrElse { "Could not save: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** Forgets the imported certificates and disconnects. */
    fun forget() {
        stop()
        TakConfig.clear(app)
        _contacts.value = emptyList()
        _chats.value = emptyList()
        _state.value = LinkState("not_set_up")
    }

    /** Sends a chat message into TAK ("All Chat Rooms"). False if not connected. */
    fun sendChat(text: String): Boolean {
        val t = tak
        if (t == null || !_state.value.connected) return false
        val xml = Cot.chat(installUid(), "BASTION", text)
        // Network I/O off the main thread.
        Thread { t.send(xml) }.start()
        _chats.value = (listOf(TakChat("BASTION (you)", text, "All Chat Rooms", System.currentTimeMillis())) + _chats.value).take(MAX_CHATS)
        return true
    }

    private fun onEvent(xml: String) {
        when (val e = Cot.parse(xml)) {
            is Cot.Event.Contact -> {
                val others = _contacts.value.filterNot { it.uid == e.contact.uid }
                _contacts.value = (listOf(e.contact) + others).take(MAX_CONTACTS)
            }
            is Cot.Event.Chat -> _chats.value = (listOf(e.chat) + _chats.value).take(MAX_CHATS)
            Cot.Event.Other -> Unit
        }
    }

    /** A random id for this install, used as BASTION's TAK uid (not tied to the person or phone). */
    private fun installUid(): String {
        val p = app.getSharedPreferences("link", Context.MODE_PRIVATE)
        return p.getString("uid", null) ?: ("BASTION-" + UUID.randomUUID().toString().take(8).uppercase())
            .also { p.edit().putString("uid", it).apply() }
    }

    companion object {
        private const val MAX_CONTACTS = 200
        private const val MAX_CHATS = 100
        private const val ORIGINAL = "original.zip"

        @Volatile private var inst: LinkClient? = null
        fun get(context: Context): LinkClient =
            inst ?: synchronized(this) { inst ?: LinkClient(context.applicationContext).also { inst = it } }
    }
}
