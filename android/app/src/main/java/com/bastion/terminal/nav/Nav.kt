package com.bastion.terminal.nav

import com.bastion.terminal.link.Cot
import com.bastion.terminal.link.TakContact
import java.util.Locale

/**
 * Navigation arithmetic. Deliberately **not** done by the AI model: small
 * models invent numbers, and a wrong bearing is worse than no bearing. Every
 * figure BASTION gives for distance or direction comes from here. Pure JVM.
 */
object Nav {
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float? = null, val time: Long = 0L)

    /** Initial great-circle bearing from point 1 to point 2, degrees true, 0..359. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.round(Math.toDegrees(Math.atan2(y, x))).toInt() % 360 + 360) % 360
    }

    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    fun compass(bearing: Int): String = POINTS[(((bearing % 360) + 360) % 360 * 16 + 180) / 360 % 16]

    fun distanceText(meters: Double): String = when {
        meters < 1000 -> "${Math.round(meters / 10) * 10} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000)
        else -> "${Math.round(meters / 1000)} km"
    }

    /** "1.4 km at 045° NE" from [from] to the contact, or null without both positions. */
    fun legText(from: Fix?, to: TakContact): String? {
        if (from == null || !to.hasPosition) return null
        val d = Cot.distanceMeters(from.lat, from.lon, to.lat, to.lon)
        if (d < 15) return "at your position"
        val b = bearingDeg(from.lat, from.lon, to.lat, to.lon)
        return "${distanceText(d)} at ${String.format(Locale.US, "%03d", b)}° ${compass(b)}"
    }

    fun ageText(seenAt: Long, now: Long): String {
        val s = ((now - seenAt) / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    /**
     * Who a navigation question is about: "where is bravo" / "nav to sim b" /
     * "distance to medic". Returns the name asked for, or null if the text is
     * not a navigation question (it then goes to the library as usual).
     */
    fun targetOf(question: String): String? {
        val q = question.trim().trimEnd('?', '.', '!')
        val m = Regex(
            "^(?:where(?:'s| is| are)|nav(?:igate)?(?: me)? to|guide(?: me)? to|distance to|bearing to|how far (?:is|to)|locate|find)\\s+(.{2,40})$",
            RegexOption.IGNORE_CASE,
        ).find(q) ?: return null
        return m.groupValues[1].trim().removePrefix("the ").trim().takeIf { it.isNotEmpty() }
    }

    /** Best contact for a spoken name: exact, then word-prefix, then substring match on the callsign. */
    fun findContact(name: String, contacts: List<TakContact>): TakContact? {
        val n = name.lowercase()
        fun words(c: TakContact) = c.callsign.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return contacts.firstOrNull { it.callsign.equals(name, ignoreCase = true) }
            ?: contacts.firstOrNull { c -> n.split(' ').all { part -> words(c).any { it.startsWith(part) } } }
            ?: contacts.firstOrNull { it.callsign.lowercase().contains(n) }
    }

    /**
     * The one-message answer to "where is X", computed (never generated).
     * [from]/[fromName]: whose position distances are measured from.
     */
    fun answer(target: String, contacts: List<TakContact>, from: Fix?, fromName: String, now: Long): String {
        val c = findContact(target, contacts)
            ?: return "No position known for \"$target\". Known: " +
                (contacts.filter { it.hasPosition }.take(4).joinToString(", ") { it.callsign.substringBefore(" - ") }.ifEmpty { "none" }) + "."
        if (!c.hasPosition) return "${c.callsign}: no position reported."
        val pos = String.format(Locale.US, "%.5f, %.5f", c.lat, c.lon)
        val leg = legText(from, c)
        return c.callsign.substringBefore(" - ") + ": " +
            (if (leg != null) "$leg from $fromName, " else "") + "at $pos, seen ${ageText(c.seenAt, now)}."
    }
}
