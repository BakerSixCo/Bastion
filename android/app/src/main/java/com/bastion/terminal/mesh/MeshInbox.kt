package com.bastion.terminal.mesh

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** One received mesh text, as the Meshtastic app announced it. */
data class MeshMessage(val id: Long, val time: Long, val sender: String, val channel: String, val text: String)

/**
 * Local store of received mesh messages (app-private SQLite; never leaves the
 * phone). Filled by [MeshInboxService] from the Meshtastic app's message
 * notifications.
 */
class MeshInbox private constructor(app: Context) {

    private val helper = object : SQLiteOpenHelper(app, "mesh.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, " +
                    "sender TEXT NOT NULL, channel TEXT NOT NULL, text TEXT NOT NULL, " +
                    "UNIQUE(time, sender, text))"
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) = Unit
    }

    private val _changes = MutableStateFlow(0)
    /** Bumps whenever the inbox changes (UI re-queries). */
    val changes: StateFlow<Int> = _changes

    /** Inserts if new (same time + sender + text is the same message re-announced). */
    fun add(time: Long, sender: String, channel: String, text: String): Boolean {
        if (text.isBlank()) return false
        val row = helper.writableDatabase.insertWithOnConflict(
            "messages", null,
            ContentValues().apply {
                put("time", time); put("sender", sender); put("channel", channel); put("text", text)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (row >= 0) _changes.value = _changes.value + 1
        return row >= 0
    }

    suspend fun recent(limit: Int = 100): List<MeshMessage> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id,time,sender,channel,text FROM messages ORDER BY time DESC, id DESC LIMIT $limit", null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(MeshMessage(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("messages", null, null)
        _changes.value = _changes.value + 1
    }

    companion object {
        @Volatile private var inst: MeshInbox? = null
        fun get(context: Context): MeshInbox =
            inst ?: synchronized(this) { inst ?: MeshInbox(context.applicationContext).also { inst = it } }

        /** Has the user granted BASTION notification access (Android special access)? */
        fun accessGranted(context: Context): Boolean {
            val me = ComponentName(context, MeshInboxService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').any { it == me }
        }
    }
}

/**
 * Reads incoming mesh messages from the **Meshtastic app's notifications**.
 *
 * Why this way (decision 2026-11-22): Meshtastic Android 2.8.4 exports no
 * API for other apps, and BASTION talking to the radio itself would need a
 * Bluetooth permission and would lock Meshtastic — and its TAK server —
 * out of the radio. Meshtastic's message notifications are
 * structured (sender, text, time, channel), so listening to them gives
 * BASTION an inbox without touching the radio or the network, while
 * Meshtastic keeps the radio.
 *
 * Scope, enforced here: only notifications from the Meshtastic package, and
 * only message-category ones, are looked at; everything else is ignored
 * untouched. Nothing is dismissed or modified. The user must grant
 * "notification access" in Android settings; without it this never runs.
 *
 * Limits (honest): a message is only seen if Meshtastic notifies about it —
 * not when its notifications are off/muted, and possibly not while that chat
 * is open in Meshtastic. Positions and node lists are not available this way.
 */
class MeshInboxService : NotificationListenerService() {

    override fun onListenerConnected() {
        // Pick up messages that arrived before BASTION was listening.
        runCatching { activeNotifications?.forEach(::ingest) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = ingest(sbn)

    private fun ingest(sbn: StatusBarNotification) {
        if (sbn.packageName != MeshBridge.MESHTASTIC_PACKAGE) return
        val n = sbn.notification ?: return
        if (n.category != NotificationCompat.CATEGORY_MESSAGE) return
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
            ?: return
        val channel = style.conversationTitle?.toString().orEmpty()
        val inbox = MeshInbox.get(this)
        // Only the new messages of this notification; `historicMessages` are older context
        // (including the user's own sent texts).
        val auto = MeshAutoResponder.isEnabled(this)
        // Meshtastic's own direct-reply action (the one with a text input), if offered.
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val wipedAt = com.bastion.terminal.security.AppLock.wipedAt(this)
        for (m in style.messages) {
            val text = m.text?.toString() ?: continue
            if (m.timestamp <= wipedAt) continue // received before a wipe: never bring it back
            val sender = m.person?.name?.toString() ?: "Unknown node"
            val isNew = inbox.add(m.timestamp, sender, channel, text)
            // Only brand-new messages can trigger an automatic answer (never a re-announced one).
            if (isNew && auto) {
                MeshAutoResponder.offer(this, MeshMessage(0, m.timestamp, sender, channel, text), reply)
            }
        }
    }
}
