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
 * - BASTION itself still has no INTERNET or Bluetooth permission. Talking
 *   to a radio directly would need one of them and would lock the
 *   Meshtastic app (and its ATAK plugin) out of the radio; that is an
 *   explicit future decision, not a side effect.
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
