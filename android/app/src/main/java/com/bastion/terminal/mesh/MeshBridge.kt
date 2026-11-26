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

    private val sentenceBreak = Regex("(?<=[.!?;])\\s+")

    private fun packingUnits(text: String): List<String> = text.split('\n').flatMap { raw ->
        val line = raw.trim { it == ' ' || it in '\t'..'\r' }
        when {
            line.isEmpty() -> emptyList()
            byteLength(line) <= MAX_BYTES - 10 -> listOf(line)
            else -> line.split(sentenceBreak)
        }
    }

    /**
     * Packs approved text as a whole or returns null. Prefixes count toward each part's byte
     * budget. Keep this in sync with tools/knowledge/build.pl. Line and sentence breaks may
     * become spaces on the radio; the words, punctuation and interior text are preserved.
     */
    internal fun split(text: String, maxParts: Int, prefix: (Int, Int) -> String): List<String>? =
        packUnits(packingUnits(text), maxParts, prefix)

    private fun packUnits(units: List<String>, maxParts: Int, prefix: (Int, Int) -> String): List<String>? {
        require(maxParts > 0)
        if (units.isEmpty()) return null

        fun pack(total: Int): List<String>? {
            val parts = ArrayList<String>(total)
            var current = ""
            for (unit in units) {
                val joined = if (current.isEmpty()) unit else "$current $unit"
                val header = prefix(parts.size + 1, total)
                if (byteLength(header + joined) <= MAX_BYTES) {
                    current = joined
                    continue
                }
                if (current.isEmpty() || parts.size + 1 == total) return null
                parts += header + current
                current = unit
                if (byteLength(prefix(parts.size + 1, total) + current) > MAX_BYTES) return null
            }
            parts += prefix(parts.size + 1, total) + current
            return parts
        }

        // Try real part totals so the budget always includes the actual prefix.
        for (total in 1..maxParts) {
            val parts = pack(total)
            if (parts?.size == total) return parts
        }
        return null
    }

    /**
     * Normalizes the initial text for the manual share editor. The editor shows the byte
     * count and requires the user to edit any over-limit message before handing it over.
     * The second value in the editor's existing API indicates automatic shortening.
     */
    fun fit(text: String): Pair<String, Boolean> {
        val clean = text
            .replace(Regex("\\s*\\[\\d+(\\s*,\\s*\\d+)*]"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "- ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .trim()
        return clean to false
    }

    /**
     * Packs up to three route messages after the initial position reply. The summary
     * and each leg stay whole. If the route is too long, keep the longest prefix of
     * legs that fits with the omission notice included in the byte budget.
     */
    internal fun splitForNavigation(summary: String, legs: List<String>): List<String> {
        val maxParts = 3
        val prefix: (Int, Int) -> String = { part, total -> "NAV ${part + 1}/${total + 1}: " }
        fun omitted(count: Int) = "+$count more legs, too long for radio."

        val bodyBudget = MAX_BYTES - byteLength(prefix(1, maxParts))
        val summaryBytes = byteLength(summary)
        if (summaryBytes > bodyBudget) {
            val notice = "Route summary too long for radio."
            return listOf(prefix(1, 1) + if (legs.isEmpty()) notice else "$notice ${omitted(legs.size)}")
        }

        // Bound the search by the total body budget before trying actual packet breaks.
        var bytesLeft = maxParts * bodyBudget - summaryBytes
        val upperBound = legs.takeWhile { leg ->
            bytesLeft -= byteLength(leg)
            bytesLeft >= 0
        }.size
        for (kept in upperBound downTo 0) {
            val units = listOf(summary) + legs.take(kept)
            val left = legs.size - kept
            val withNotice = if (left == 0) units else units + omitted(left)
            packUnits(withNotice, maxParts, prefix)?.let { return it }
        }
        error("The route summary and omission notice must fit in separate messages.")
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
