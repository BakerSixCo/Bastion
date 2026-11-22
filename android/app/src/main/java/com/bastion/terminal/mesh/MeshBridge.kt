package com.bastion.terminal.mesh

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * BASTION → mesh, through the Meshtastic app.
 *
 * How and why (found 2026-11-22 with Meshtastic Android 2.8.4):
 * - The Meshtastic app owns the radio link (Bluetooth / USB / network). Its
 *   old bindable service for third-party apps is no longer exported, so
 *   BASTION cannot read mesh messages or nodes from it.
 * - What it does accept is a standard Android text share. BASTION hands the
 *   text over; the user picks the channel/contact **and presses Send in
 *   Meshtastic** — nothing is transmitted without that tap.
 * - BASTION has no Bluetooth permission and does not talk to the radio
 *   itself: that would lock the Meshtastic app (and its TAK server) out of
 *   the radio.
 */
object MeshBridge {
    const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"

    /** Meshtastic text payload limit (bytes of UTF-8), as enforced by the Meshtastic app. */
    const val MAX_BYTES = 200

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(MESHTASTIC_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun byteLength(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /**
     * Makes a text fit one mesh message: whitespace collapsed, AI citation
     * markers like "[1]" dropped (they mean nothing to the receiver), then
     * cut at a word boundary with "…" if still too long. Never splits a
     * multi-byte character. Returns the text and whether it was shortened.
     */
    fun fit(text: String, maxBytes: Int = MAX_BYTES): Pair<String, Boolean> {
        val clean = text
            .replace(Regex("\\s*\\[\\d+(\\s*,\\s*\\d+)*]"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "- ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .trim()
        if (byteLength(clean) <= maxBytes) return clean to false
        val ell = "…"
        val budget = maxBytes - byteLength(ell)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val s = String(Character.toChars(cp))
            val n = byteLength(s)
            if (used + n > budget) break
            sb.append(s)
            used += n
            i += Character.charCount(cp)
        }
        // Prefer ending on a word if that doesn't throw away much.
        val cut = sb.toString()
        val lastSpace = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
        val out = if (lastSpace > cut.length * 2 / 3) cut.substring(0, lastSpace) else cut
        return (out.trimEnd() + ell) to true
    }

    /**
     * Splits an answer into at most [maxParts] mesh messages, each within
     * [MAX_BYTES] including its prefix ("AI 1/3: "). Breaks between lines
     * (numbered steps) and then between sentences, never inside a word or a
     * multi-byte character. If it still doesn't fit, the last part ends with
     * "…". Citation markers are removed like in [fit]. [prefix] gets the part
     * number and the total.
     */
    fun split(text: String, maxParts: Int, prefix: (Int, Int) -> String): List<String> {
        val clean = fit(text, Int.MAX_VALUE).first
        // Units that should stay whole: lines, and sentences inside long lines.
        val units = clean.split('\n').filter { it.isNotBlank() }.flatMap { line ->
            if (byteLength(line) <= MAX_BYTES - 10) listOf(line.trim())
            else line.split(Regex("(?<=[.!?;])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        fun pack(total: Int): List<String> {
            val out = ArrayList<String>()
            var cur = ""
            for (u in units) {
                val budget = MAX_BYTES - byteLength(prefix(out.size + 1, total))
                val joined = if (cur.isEmpty()) u else "$cur $u"
                if (byteLength(joined) <= budget) { cur = joined; continue }
                if (cur.isNotEmpty()) { out += cur; cur = "" }
                // A single unit longer than one message: cut it at a word.
                cur = if (byteLength(u) <= budget) u else fit(u, budget).first
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
        var bodies = pack(maxParts)
        if (bodies.size > maxParts) {
            val head = bodies.take(maxParts - 1)
            val budget = MAX_BYTES - byteLength(prefix(maxParts, maxParts))
            val last = fit(bodies.drop(maxParts - 1).joinToString(" "), budget).first.trimEnd('…', ' ') + "…"
            bodies = head + fit(last, budget).first
        }
        val n = bodies.size.coerceAtLeast(1)
        return if (bodies.isEmpty()) listOf(prefix(1, 1).trim())
        else bodies.mapIndexed { i, b -> prefix(i + 1, n) + b }
    }

    /** Hands [text] to the Meshtastic app's share screen. False if it can't be opened. */
    fun share(context: Context, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(MESHTASTIC_PACKAGE)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent); true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
