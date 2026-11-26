package com.bastion.terminal.mesh

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.bastion.terminal.ai.Card
import com.bastion.terminal.ai.CardStore
import com.bastion.terminal.link.LinkClient
import com.bastion.terminal.link.TakContact
import com.bastion.terminal.nav.LocationSource
import com.bastion.terminal.nav.Nav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Answers commands that arrive over the mesh — for people who do **not**
 * have BASTION — and replies through the Meshtastic app.
 *
 * **Standing rule (owner, 2026-11-22): a leading symbol marks a message for
 * BASTION; everything else is ordinary chat and is never answered.**
 *   `?question`  request an approved card's radio version
 *   `@callsign`  where is that unit — `@base` for this phone (computed, not AI)
 *   `@gas`       the nearest place of a kind on the offline maps (fuel, hospital, pharmacy, water…)
 *   `+Name lat,lon`  save this pin on the map (nothing is sent back)
 * New actions get a new symbol; a word or a "looks like a question" guess is
 * never a trigger. (That guess was tried for an hour and withdrawn.)
 *
 * How it sends without a tap: Meshtastic's message notification carries a
 * "Reply" action (direct reply). BASTION fills it in and fires it. Meshtastic
 * still owns the radio.
 *
 * Guard rails — this transmits on the owner's behalf:
 * - Off by default; approved radios only; private channels only unless the
 *   owner also allows public ones.
 * - Card questions match a unique approved wording. Replies contain the card's
 *   approved radio lines or a fixed notice.
 * - Positions and distances are computed ([Nav]), never generated.
 * - A few short numbered messages per card or navigation reply, marked "AI" / "NAV".
 * - One at a time, a small queue, a per-sender cool-down.
 */
object MeshAutoResponder {
    private const val TAG = "BastionMesh"
    private const val PREFS = "mesh"
    private const val KEY_ENABLED = "auto_answer"
    private const val KEY_PUBLIC = "auto_answer_public"
    const val REPLY_PREFIX = "AI: "
    /** Computed (not AI-generated) navigation replies. */
    const val NAV_PREFIX = "NAV: "
    private const val SENDER_COOLDOWN_MS = 20_000L
    /** At most this many radio messages per answer. */
    private const val MAX_PARTS = 4
    private const val PART_GAP_MS = 4_000L
    private const val NOT_COVERED = "Not covered by radio."
    private const val UNAVAILABLE_SUFFIX = "Too long to send safely. Get to someone with BASTION or medical help."
    private val QUESTION_WHITESPACE = Regex("\\s+")

    /** What a mesh message asks BASTION to do. */
    sealed class Command {
        data class Ask(val question: String) : Command()
        data class Locate(val target: String) : Command()
        /** "+Name lat,lon": save this pin on the map. Nothing is sent back. */
        data class AddPin(val name: String, val lat: Double, val lon: Double) : Command()
    }

    /**
     * The command in a mesh message, or null for ordinary chat. Only a leading
     * symbol counts: `?` requests a card, `@` locates a unit.
     */
    fun commandIn(text: String): Command? {
        val t = text.trim()
        if (t.startsWith(REPLY_PREFIX) || t.startsWith(NAV_PREFIX) || t.startsWith("AI ")) return null
        return when {
            t.startsWith("?") -> t.drop(1).trim().trimEnd('?', ' ').takeIf { it.length >= 3 }?.let { Command.Ask(it) }
            t.startsWith("@") -> t.drop(1).trim().takeIf { it.length in 2..40 }?.let { Command.Locate(it) }
            t.startsWith("+") -> com.bastion.terminal.map.Pins.parse(t)?.let { (n, la, lo) -> Command.AddPin(n, la, lo) }
            else -> null
        }
    }

    /**
     * Channel names Meshtastic gives its **default** channel (the modem preset name). The
     * default channel is encrypted with a key everyone knows, so it is effectively public.
     * BASTION cannot see a channel's key — only its name — so a default name is treated as
     * public. (A private channel deliberately named like a preset would be misjudged as
     * public: the safe direction.)
     */
    private val PUBLIC_CHANNEL_NAMES = setOf(
        "longfast", "longslow", "longmoderate", "longturbo", "verylongslow", "mediumfast", "mediumslow",
        "shortfast", "shortslow", "shortturbo", "primary", "default", "",
    )

    fun isPublicChannel(name: String): Boolean = name.trim().lowercase() in PUBLIC_CHANNEL_NAMES

    /** Whether the owner allowed auto-answers on public (default-key) channels. Default: no. */
    fun publicAllowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PUBLIC, false)

    fun setPublicAllowed(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PUBLIC, on).apply()
    }

    private class Job(val msg: MeshMessage, val command: Command, val reply: Notification.Action)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Job>(capacity = 3)
    private val lastBySender = HashMap<String, Long>()
    @Volatile private var started = false

    private val _status = MutableStateFlow("")
    /** Last thing the auto-responder did, in plain words (shown in the Mesh tab). */
    val status: StateFlow<String> = _status

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        _status.value = if (on) "Auto-answer on. Waiting for “?question” or “@callsign” from an approved radio." else ""
    }

    /** Called by the inbox for each **new** message when auto-answer is on. */
    fun offer(context: Context, msg: MeshMessage, reply: Notification.Action?) {
        val command = commandIn(msg.text) ?: return
        if (isPublicChannel(msg.channel) && !publicAllowed(context)) {
            _status.value = "Not answered: ${msg.sender} asked on the public channel “${msg.channel}”. " +
                "Auto-answer only replies on private channels."
            return
        }
        // Approved radios only (owner decision). Unknown askers get silence, not a refusal
        // message — a reply would confirm there is a BASTION listening.
        val radios = ApprovedRadios.get(context)
        if (!radios.isApproved(msg.sender)) {
            radios.notePending(msg.sender)
            _status.value = "Not answered: “${msg.sender}” is not an approved radio. Approve it below to answer its questions."
            return
        }
        if (command is Command.AddPin) {
            // A pin needs no answer and no AI: save it and say so on the Mesh tab.
            com.bastion.terminal.map.Pins.get(context).add(command.name, command.lat, command.lon, msg.sender)
            _status.value = "Saved pin “${command.name}” from ${msg.sender}. It is on the Map."
            Log.i(TAG, "pin-saved sender=${msg.sender}")
            return
        }
        if (reply == null) {
            _status.value = "Message from ${msg.sender}, but Meshtastic offered no reply action — not answered."
            return
        }
        val now = System.currentTimeMillis()
        synchronized(lastBySender) {
            val last = lastBySender[msg.sender] ?: 0L
            if (now - last < SENDER_COOLDOWN_MS) {
                _status.value = "Skipped a message from ${msg.sender}: sent again within ${SENDER_COOLDOWN_MS / 1000} s."
                return
            }
            lastBySender[msg.sender] = now
        }
        start(context.applicationContext)
        if (!queue.trySend(Job(msg, command, reply)).isSuccess) {
            _status.value = "Busy: skipped a message from ${msg.sender} (queue full)."
        }
    }

    private fun start(app: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            for (job in queue) {
                runCatching { handle(app, job) }.onFailure {
                    Log.w(TAG, "auto-answer failed: ${it.message}")
                    _status.value = "Could not answer ${job.msg.sender}: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }

    private suspend fun handle(app: Context, job: Job) {
        when (val c = job.command) {
            is Command.Locate -> locate(app, job, c.target)
            is Command.AddPin -> Unit // saved when it arrived
            // "?where is base" still works: a navigation question is computed, not generated.
            is Command.Ask -> Nav.targetOf(c.question)?.let { locate(app, job, it) } ?: ask(app, job, c.question)
        }
    }

    /** `@callsign`: a computed position, measured from the asker's own position when known. */
    private suspend fun locate(app: Context, job: Job, target: String) {
        val who = job.msg.sender
        val contacts = LinkClient.get(app).contacts.value
        val asker = contacts.firstOrNull { it.hasPosition && it.callsign.contains(who, ignoreCase = true) }
        val mine = LocationSource.get(app).fix.value
        // "@base" / "@hq" / "@bastion" = this phone, when it has a GPS fix.
        val self = mine?.let {
            TakContact(
                "BASTION-SELF", "BASTION (base, hq, you)", "a-f-G-U-C", it.lat, it.lon, null, null, null,
                if (it.time > 0) it.time else System.currentTimeMillis(),
            )
        }
        val from = asker?.let { Nav.Fix(it.lat, it.lon) } ?: mine
        val known = contacts.filterNot { it.uid == asker?.uid } + listOfNotNull(self)
        // "@gas", "@hospital": the nearest place of that kind on the offline maps — unless a unit goes by that name.
        val kind = Nav.placeKind(target)
        if (kind != null && Nav.findContact(target, known) == null) {
            nearest(app, job, kind, from, if (asker != null) "you" else "BASTION")
            return
        }
        val body = Nav.answer(
            target, known, from,
            if (asker != null) "you" else "BASTION", System.currentTimeMillis(),
        )
        // Plain ASCII for radio screens: the degree sign showed as "##" on the simulated node.
        val text = MeshBridge.fit(NAV_PREFIX + body.replace("°", " deg")).first
        send(app, job.reply, text)
        MeshInbox.get(app).add(System.currentTimeMillis(), "BASTION NAV → $who", job.msg.channel, text)
        Log.i(TAG, "nav-answered sender=$who bytes=${MeshBridge.byteLength(text)}")
        _status.value = "Sent $who a position for “$target”."
        // If an offline map covers both ends, follow with a walking route worked out from the
        // ground (computed like the bearing above — the AI model is not involved).
        val to = Nav.findContact(target, known)?.takeIf { it.hasPosition }
        val route = if (from != null && to != null) {
            runCatching { com.bastion.terminal.map.MapPacks.get(app).routeBetween(from.lat, from.lon, to.lat, to.lon) }.getOrNull()
        } else null
        if (route != null && route.meters > 150) {
            val steps = "Route on foot: " + route.summary() + ".\n" +
                route.legs.mapIndexed { i, leg -> leg.text(i + 1) }.joinToString("\n")
            val parts = MeshBridge.splitForNavigation(steps.replace("°", " deg"), 3) { i, n -> "NAV ${i + 1}/${n + 1}: " }
            for (part in parts) {
                delay(PART_GAP_MS)
                send(app, job.reply, part)
                MeshInbox.get(app).add(System.currentTimeMillis(), "BASTION NAV → $who", job.msg.channel, part)
            }
            Log.i(TAG, "nav-route sender=$who parts=${parts.size} legs=${route.legs.size}")
            _status.value = "Sent $who a position and a walking route for “$target”."
        }
    }

    private fun questionKey(question: String): String =
        question.trim().trimEnd('?', ' ').lowercase().replace(QUESTION_WHITESPACE, " ")

    /** The complete packets for a card question. Ambiguous aliases get the fixed no-match reply. */
    internal fun answerFor(question: String, cards: List<Card>): List<String> {
        val key = questionKey(question)
        val card = cards.singleOrNull { c -> c.asks.any { questionKey(it) == key } }
            ?: return listOf(NOT_COVERED)
        val approved = card.answer + card.warnings
        if (card.radio.isNotEmpty() && card.radio.all { it.isNotBlank() && it in approved }) {
            val body = card.radio.mapIndexed { i, line -> "${i + 1}. $line" }
                .joinToString("\n", prefix = card.title + "\n")
            MeshBridge.split(body, MAX_PARTS) { i, total ->
                if (total == 1) REPLY_PREFIX else "AI $i/$total: "
            }?.let { return it }
        }
        val unavailable = "Not available by radio: ${card.title}. $UNAVAILABLE_SUFFIX"
        return MeshBridge.split(unavailable, MAX_PARTS) { _, _ -> "" }
            ?: listOf("Not available by radio. $UNAVAILABLE_SUFFIX")
    }

    private suspend fun ask(app: Context, job: Job, question: String) {
        val who = job.msg.sender
        val parts = answerFor(question, CardStore.get(app))
        for ((i, text) in parts.withIndex()) {
            if (i > 0) delay(PART_GAP_MS)
            send(app, job.reply, text)
            MeshInbox.get(app).add(System.currentTimeMillis(), "BASTION → $who", job.msg.channel, text)
        }
        Log.i(TAG, "auto-answered sender=$who parts=${parts.size}")
        _status.value = "Answered $who" + if (parts.size > 1) " in ${parts.size} messages." else "."
    }

    /** Answers "@gas": the nearest places of one kind, then a walking route to the first if the maps can work one out. */
    private suspend fun nearest(app: Context, job: Job, kind: String, from: Nav.Fix?, fromName: String) {
        val who = job.msg.sender
        val maps = com.bastion.terminal.map.MapPacks.get(app)
        val found = if (from == null) emptyList() else runCatching { maps.nearest(from.lat, from.lon, kind, 2) }.getOrDefault(emptyList())
        val body = when {
            from == null -> "I have no position for you or for BASTION, so I cannot find the nearest ${kind.lowercase()}."
            maps.packs.value.none { p -> p.layers.any { it.point } } -> "My maps have no places on them."
            else -> Nav.placeAnswer(
                kind,
                found.map { p ->
                    Nav.NearPlace(
                        p.name, p.meters, Nav.bearingDeg(from.lat, from.lon, p.lat, p.lon),
                        Regex("\\+?[0-9][0-9 ()-]{6,}").find(p.detail)?.value?.trim(),
                    )
                },
                fromName, maps.packs.value.lastOrNull { p -> p.layers.any { it.point } }?.built.orEmpty(),
            )
        }
        val text = MeshBridge.fit(NAV_PREFIX + body.replace("°", " deg")).first
        send(app, job.reply, text)
        MeshInbox.get(app).add(System.currentTimeMillis(), "BASTION NAV → $who", job.msg.channel, text)
        Log.i(TAG, "nav-nearest sender=$who kind=$kind found=${found.size} bytes=${MeshBridge.byteLength(text)}")
        _status.value = "Sent $who the nearest ${kind.lowercase()}."
        val to = found.firstOrNull() ?: return
        val route = runCatching { maps.routeBetween(from!!.lat, from.lon, to.lat, to.lon) }.getOrNull() ?: return
        if (route.meters <= 150) return
        val steps = "Route on foot to ${to.name}: " + route.summary() + ".\n" +
            route.legs.mapIndexed { i, leg -> leg.text(i + 1) }.joinToString("\n")
        val parts = MeshBridge.splitForNavigation(steps.replace("°", " deg"), 3) { i, n -> "NAV ${i + 1}/${n + 1}: " }
        for (part in parts) {
            delay(PART_GAP_MS)
            send(app, job.reply, part)
            MeshInbox.get(app).add(System.currentTimeMillis(), "BASTION NAV → $who", job.msg.channel, part)
        }
        Log.i(TAG, "nav-route sender=$who parts=${parts.size} legs=${route.legs.size}")
    }

    /** Fires Meshtastic's own direct-reply action with [text]. */
    private fun send(app: Context, action: Notification.Action, text: String) {
        require(MeshBridge.byteLength(text) <= MeshBridge.MAX_BYTES) { "Reply exceeds the mesh byte limit" }
        val inputs = action.remoteInputs ?: error("reply action has no text input")
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(app, 0, intent)
    }
}
