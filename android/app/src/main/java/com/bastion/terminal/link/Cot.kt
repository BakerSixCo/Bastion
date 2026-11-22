package com.bastion.terminal.link

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A unit / contact / marker on the TAK picture. */
data class TakContact(
    val uid: String,
    val callsign: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val battery: Int?,
    val team: String?,
    val role: String?,
    val seenAt: Long,
) {
    val hasPosition: Boolean get() = !(lat == 0.0 && lon == 0.0)
    val isMeshNode: Boolean get() = uid.startsWith("MESHTASTIC-")
}

data class TakChat(val sender: String, val text: String, val room: String, val time: Long)

/**
 * Minimal Cursor-on-Target (CoT) XML reading and writing — the format TAK
 * uses. Pure JVM (unit-tested). Everything parsed here is **data from the
 * network picture**: displayed, never executed or obeyed.
 */
object Cot {
    sealed class Event {
        data class Contact(val contact: TakContact) : Event()
        data class Chat(val chat: TakChat) : Event()
        /** Keep-alives, protocol negotiation, anything BASTION doesn't show. */
        data object Other : Event()
    }

    private fun attr(xml: String, tag: String, name: String): String? {
        val t = Regex("<$tag\\b[^>]*>").find(xml)?.value ?: return null
        return Regex("\\b$name\\s*=\\s*(['\"])(.*?)\\1").find(t)?.groupValues?.get(2)?.let(::unescape)
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun parse(xml: String, now: Long = System.currentTimeMillis()): Event {
        val type = attr(xml, "event", "type") ?: return Event.Other
        val uid = attr(xml, "event", "uid") ?: return Event.Other
        return when {
            // Control traffic: pings, pongs, protocol support.
            type.startsWith("t-") -> Event.Other
            type == "b-t-f" -> {
                val text = Regex("(?s)<remarks\\b[^>]*>(.*?)</remarks>").find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()
                if (text.isNullOrEmpty()) Event.Other
                else Event.Chat(
                    TakChat(
                        sender = attr(xml, "__chat", "senderCallsign") ?: "Unknown",
                        text = text.take(2000),
                        room = attr(xml, "__chat", "chatroom") ?: "",
                        time = now,
                    )
                )
            }
            // a-* = people/units; b-m-p-* = map points/markers.
            type.startsWith("a-") || type.startsWith("b-m-p") -> {
                val lat = attr(xml, "point", "lat")?.toDoubleOrNull() ?: return Event.Other
                val lon = attr(xml, "point", "lon")?.toDoubleOrNull() ?: return Event.Other
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Event.Other
                Event.Contact(
                    TakContact(
                        uid = uid.take(120),
                        callsign = (attr(xml, "contact", "callsign") ?: uid).take(80),
                        type = type,
                        lat = lat,
                        lon = lon,
                        battery = attr(xml, "status", "battery")?.toIntOrNull()?.takeIf { it in 0..100 },
                        team = attr(xml, "__group", "name"),
                        role = attr(xml, "__group", "role"),
                        seenAt = now,
                    )
                )
            }
            else -> Event.Other
        }
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

    /** A GeoChat message to "All Chat Rooms" from BASTION ([senderUid] is this install's id). */
    fun chat(senderUid: String, callsign: String, text: String, now: Long = System.currentTimeMillis()): String {
        val id = java.util.UUID.randomUUID().toString()
        val t = iso(now)
        val stale = iso(now + 24 * 3600_000L)
        val u = escape(senderUid)
        val c = escape(callsign)
        return "<event version=\"2.0\" uid=\"GeoChat.$u.All Chat Rooms.$id\" type=\"b-t-f\" time=\"$t\" start=\"$t\" " +
            "stale=\"$stale\" how=\"h-g-i-g-o\"><point lat=\"0\" lon=\"0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail><__chat parent=\"RootContactGroup\" groupOwner=\"false\" messageId=\"$id\" chatroom=\"All Chat Rooms\" " +
            "id=\"All Chat Rooms\" senderCallsign=\"$c\"><chatgrp uid0=\"$u\" uid1=\"All Chat Rooms\" id=\"All Chat Rooms\"/></__chat>" +
            "<link uid=\"$u\" type=\"a-f-G-U-C\" relation=\"p-p\"/>" +
            "<remarks source=\"BAO.F.ATAK.$u\" to=\"All Chat Rooms\" time=\"$t\">${escape(text)}</remarks></detail></event>"
    }

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
