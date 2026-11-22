package com.bastion.link

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * The bridge BASTION binds to. Protected by the signature permission
 * `com.bastion.permission.LINK`, so only an app signed with the same key can
 * reach it. Relays CoT events in both directions; stores nothing.
 *
 * Messenger protocol (mirrored in BASTION's LinkClient):
 *   in : 1 REGISTER (replyTo) · 2 UNREGISTER (replyTo) · 3 SEND_COT {xml}
 *   out: 10 STATUS {state, detail} · 11 EVENT {xml}
 */
class LinkService : Service() {
    private val clients = ArrayList<Messenger>()
    private var tak: TakClient? = null
    private var state = "idle"
    private var detail = ""
    private val main = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            REGISTER -> msg.replyTo?.let { clients.add(it); sendStatus(it); ensureStarted() }
            UNREGISTER -> msg.replyTo?.let { r -> clients.removeAll { it.binder == r.binder } }
            SEND_COT -> msg.data?.getString("xml")?.let { xml ->
                // Network I/O off the main thread.
                Thread { tak?.send(xml) }.start()
            }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        clients.clear()
        tak?.stop()
        tak = null
        return false
    }

    override fun onDestroy() {
        tak?.stop()
        super.onDestroy()
    }

    private fun ensureStarted() {
        if (tak != null) return
        val cfg = TakConfig.load(this)
        if (cfg == null) {
            update("not_set_up", "Open BASTION Link and import the TAK data package exported from Meshtastic.")
            return
        }
        tak = TakClient(
            cfg,
            onState = { s, d -> main.post { update(s, d) } },
            onEvent = { xml -> main.post { broadcast(EVENT) { putString("xml", xml) } } },
        ).also { it.start() }
    }

    private fun update(s: String, d: String) {
        state = s; detail = d
        clients.toList().forEach(::sendStatus)
    }

    private fun sendStatus(to: Messenger) = deliver(to, STATUS) { putString("state", state); putString("detail", detail) }

    private fun broadcast(what: Int, fill: Bundle.() -> Unit) = clients.toList().forEach { deliver(it, what, fill) }

    private fun deliver(to: Messenger, what: Int, fill: Bundle.() -> Unit) {
        try {
            to.send(Message.obtain(null, what).apply { data = Bundle().apply(fill) })
        } catch (_: Exception) {
            clients.remove(to) // client went away
        }
    }

    companion object {
        const val REGISTER = 1
        const val UNREGISTER = 2
        const val SEND_COT = 3
        const val STATUS = 10
        const val EVENT = 11
    }
}
