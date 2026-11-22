package com.bastion.terminal.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Where the bridge to the TAK picture stands, in plain words for the UI. */
data class LinkState(val state: String, val detail: String = "") {
    val connected: Boolean get() = state == "connected"
}

/**
 * BASTION's side of the bridge to **BASTION Link** (a separate app).
 *
 * BASTION has no INTERNET permission, and Android requires it even for a
 * connection inside the phone — so the socket to the Meshtastic app's local
 * TAK server lives in Link, which holds the permission and none of BASTION's
 * data. This class only binds to Link's service (protected by a signature
 * permission: both apps must be signed with the same key) and exchanges CoT
 * XML with it. Received XML is parsed as data ([Cot]).
 */
class LinkClient private constructor(private val app: Context) {

    private val _state = MutableStateFlow(LinkState(if (isInstalled()) "idle" else "not_installed"))
    val state: StateFlow<LinkState> = _state

    private val _contacts = MutableStateFlow<List<TakContact>>(emptyList())
    val contacts: StateFlow<List<TakContact>> = _contacts

    private val _chats = MutableStateFlow<List<TakChat>>(emptyList())
    val chats: StateFlow<List<TakChat>> = _chats

    private var service: Messenger? = null
    private var bound = false

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            STATUS -> _state.value = LinkState(msg.data.getString("state").orEmpty(), msg.data.getString("detail").orEmpty())
            EVENT -> msg.data.getString("xml")?.let(::onEvent)
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            send(REGISTER, null)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.value = LinkState("disconnected", "BASTION Link stopped.")
        }
    }

    fun isInstalled(): Boolean = runCatching { app.packageManager.getPackageInfo(LINK_PACKAGE, 0) }.isSuccess

    /** Binds to Link (which then connects to the TAK server). Safe to call repeatedly. */
    fun start() {
        if (bound) return
        if (!isInstalled()) { _state.value = LinkState("not_installed"); return }
        val intent = Intent(ACTION_BIND).setPackage(LINK_PACKAGE)
        bound = try {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        if (!bound) _state.value = LinkState(
            "refused",
            "BASTION Link refused the connection. Both apps must come from the same build (same signing key).",
        )
    }

    fun stop() {
        if (!bound) return
        send(UNREGISTER, null)
        runCatching { app.unbindService(connection) }
        bound = false
        service = null
        _state.value = LinkState("idle")
    }

    /** Sends a chat message into TAK ("All Chat Rooms"). False if not connected. */
    fun sendChat(text: String): Boolean {
        if (!_state.value.connected) return false
        val xml = Cot.chat(installUid(), "BASTION", text)
        val ok = send(SEND_COT, Bundle().apply { putString("xml", xml) })
        if (ok) _chats.value = (listOf(TakChat("BASTION (you)", text, "All Chat Rooms", System.currentTimeMillis())) + _chats.value).take(MAX_CHATS)
        return ok
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

    private fun send(what: Int, data: Bundle?): Boolean {
        val s = service ?: return false
        return try {
            s.send(Message.obtain(null, what).apply { replyTo = incoming; data?.let { this.data = it } })
            true
        } catch (_: Exception) {
            false
        }
    }

    /** A random id for this install, used as BASTION's TAK uid (not tied to the person or phone). */
    private fun installUid(): String {
        val p = app.getSharedPreferences("link", Context.MODE_PRIVATE)
        return p.getString("uid", null) ?: ("BASTION-" + UUID.randomUUID().toString().take(8).uppercase())
            .also { p.edit().putString("uid", it).apply() }
    }

    companion object {
        const val LINK_PACKAGE = "com.bastion.link"
        private const val ACTION_BIND = "com.bastion.link.BIND"
        private const val REGISTER = 1
        private const val UNREGISTER = 2
        private const val SEND_COT = 3
        private const val STATUS = 10
        private const val EVENT = 11
        private const val MAX_CONTACTS = 200
        private const val MAX_CHATS = 100

        @Volatile private var inst: LinkClient? = null
        fun get(context: Context): LinkClient =
            inst ?: synchronized(this) { inst ?: LinkClient(context.applicationContext).also { inst = it } }
    }
}
