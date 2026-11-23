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

    /** Everyday words for the kinds of place a map pack holds (see MapPack.PLACE_KINDS). */
    private val PLACE_WORDS = mapOf(
        "Fuel" to listOf("fuel", "gas", "gas station", "petrol", "diesel", "gasoline"),
        "Groceries" to listOf("groceries", "grocery", "grocery store", "food", "supermarket", "market"),
        "Convenience store" to listOf("store", "convenience store", "shop"),
        "Water" to listOf("water", "drinking water"),
        "Pharmacy" to listOf("pharmacy", "drugstore", "drug store", "chemist", "medicine"),
        "Hospital" to listOf("hospital", "er", "emergency room"),
        "Clinic" to listOf("clinic", "doctor", "doctors", "urgent care"),
        "Ambulance" to listOf("ambulance", "ems"),
        "Fire station" to listOf("fire", "fire station", "fire department"),
        "Police" to listOf("police", "sheriff", "cops"),
        "Ranger station" to listOf("ranger", "ranger station", "rangers"),
        "Vet" to listOf("vet", "veterinarian", "animal hospital"),
        "Hardware" to listOf("hardware", "hardware store", "tools"),
        "Vehicle repair" to listOf("mechanic", "repair", "tire", "tires", "tyres", "garage", "car repair"),
        "Outdoor gear" to listOf("gear", "outdoor store", "outfitter"),
        "Campground" to listOf("camp", "campground", "campsite", "camping"),
    )

    /**
     * The kind of place a locate request names — "gas", "nearest hospital",
     * "a pharmacy" — or null if it is not one (it is then a callsign).
     */
    fun placeKind(target: String): String? {
        val t = target.lowercase().trim().removePrefix("nearest ").removePrefix("closest ")
            .removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()
        return PLACE_WORDS.entries.firstOrNull { (_, words) -> t in words }?.key
    }

    /** One place for [placeAnswer]: its name, how far, which way, and a phone number if known. */
    data class NearPlace(val name: String, val meters: Double, val bearing: Int, val phone: String? = null)

    /**
     * The one-message answer to "nearest fuel", computed (never generated).
     * [built]: the date of the map data, because shops close and move.
     */
    fun placeAnswer(kind: String, places: List<NearPlace>, fromName: String, built: String): String {
        if (places.isEmpty()) return "No ${kind.lowercase()} on my maps within about 100 km of $fromName."
        fun one(p: NearPlace) = "${p.name}, ${distanceText(p.meters)} at ${String.format(Locale.US, "%03d", p.bearing)}° ${compass(p.bearing)}" +
            (p.phone?.let { " (tel $it)" } ?: "")
        return "Nearest ${kind.lowercase()} to $fromName: ${one(places[0])}." +
            (places.getOrNull(1)?.let { " Next: ${one(it.copy(phone = null))}." } ?: "") +
            (if (built.isNotBlank()) " Map data $built." else "")
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
