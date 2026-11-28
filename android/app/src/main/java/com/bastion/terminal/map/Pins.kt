package com.bastion.terminal.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A place the owner marked on the map, or that an approved radio sent. [from]: the sender's name, blank for the owner's own. */
data class Pin(val id: Long, val name: String, val lat: Double, val lon: Double, val from: String = "")

/**
 * Saved pins. Kept on this phone only. A pin travels over the mesh as one
 * short line, "+Name 44.14470,-114.91700": the leading "+" is the symbol for
 * "add this pin", in line with the rule that only a leading symbol makes
 * BASTION act on a message.
 */
class Pins private constructor(app: Context) {

    private val prefs = app.getSharedPreferences("pins", Context.MODE_PRIVATE)
    private val _pins = MutableStateFlow(load())
    val pins: StateFlow<List<Pin>> = _pins

    private fun load(): List<Pin> = runCatching {
        val a = JSONArray(prefs.getString("pins", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Pin(it.getLong("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.optString("from"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Pin>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("from", p.from))
        }
        prefs.edit().putString("pins", a.toString()).apply()
        _pins.value = list
    }

    /**
     * Saves a pin and returns it. The same name at the same spot (within about
     * 10 m) is not saved twice; past [MAX] pins the oldest received one goes.
     */
    @Synchronized
    fun add(name: String, lat: Double, lon: Double, from: String = ""): Pin {
        val clean = cleanName(name).ifBlank { "Pin" }
        _pins.value.firstOrNull { it.name == clean && Geo.meters(it.lat, it.lon, lat, lon) < 10 }?.let { return it }
        val pin = Pin((_pins.value.maxOfOrNull { it.id } ?: 0L) + 1, clean, lat, lon, from)
        var list = _pins.value + pin
        if (list.size > MAX) list = list - (list.firstOrNull { it.from.isNotBlank() } ?: list.first())
        save(list)
        return pin
    }

    @Synchronized
    fun remove(id: Long) = save(_pins.value.filterNot { it.id == id })

    companion object {
        const val MAX = 300

        @Volatile private var inst: Pins? = null
        fun get(context: Context): Pins =
            inst ?: synchronized(this) { inst ?: Pins(context.applicationContext).also { inst = it } }

        /** A name safe to send and show: one line, no leading symbol, at most 24 characters. */
        fun cleanName(name: String): String =
            name.replace(Regex("[\\r\\n\\t]+"), " ").trim().trimStart('+', '?', '@').trim().take(24).trim()

        /** The line that carries a pin over the mesh: "+Camp 44.14470,-114.91700". */
        fun shareText(name: String, lat: Double, lon: Double): String =
            String.format(Locale.US, "+%s %.5f,%.5f", cleanName(name).ifBlank { "Pin" }, lat, lon)

        private val LINE = Regex("^\\+\\s*(.{1,24}?)\\s+(-?\\d{1,2}\\.\\d{3,7})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,7})\\s*$")

        /** The pin a mesh message carries (name, latitude, longitude), or null if it is not a pin line. */
        fun parse(text: String): Triple<String, Double, Double>? {
            val m = LINE.find(text.trim()) ?: return null
            val lat = m.groupValues[2].toDoubleOrNull() ?: return null
            val lon = m.groupValues[3].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val name = cleanName(m.groupValues[1])
            return if (name.isBlank()) null else Triple(name, lat, lon)
        }
    }
}
