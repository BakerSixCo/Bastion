package com.bastion.terminal.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bastion.terminal.link.Cot
import com.bastion.terminal.map.Guidance
import com.bastion.terminal.map.MapPacks
import com.bastion.terminal.map.RoadGraph
import com.bastion.terminal.map.TerrainRoute
import com.bastion.terminal.nav.LocationSource
import com.bastion.terminal.nav.Nav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The route being planned or driven, kept for the whole app process (like AskSession), so
 * turn-by-turn keeps speaking, following the GPS and rerouting while another tab is open or the
 * screen turns. The map screen only shows it. Routes and guidance are computed (map/RoadGraph,
 * map/Guidance, map/TerrainRoute), never produced by the AI.
 */
class RouteSession private constructor(private val app: Context) {
    private val store = MapPacks.get(app)
    private val location = LocationSource.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var start by mutableStateOf<Pair<Double, Double>?>(null)
    var dest by mutableStateOf<Pair<Double, Double>?>(null)
    var destName by mutableStateOf("")
    var route by mutableStateOf<TerrainRoute.Route?>(null)
    var routeNote by mutableStateOf("")
    var drive by mutableStateOf(false)
    var vehicle by mutableStateOf(RoadGraph.Vehicle.CAR)
    var drives by mutableStateOf<List<RoadGraph.Route>>(emptyList())
    var chosen by mutableStateOf(0)
    val blockedAt = mutableStateListOf<Pair<Double, Double>>()
    var navigating by mutableStateOf(false); private set
    var voiceOn by mutableStateOf(true)
    var navState by mutableStateOf<Guidance.State?>(null); private set
    /** Bumped whenever a new route list arrives, so the screen can reset its own view state. */
    var planned by mutableStateOf(0); private set

    private var guidanceFor: RoadGraph.Route? = null
    private var guidanceCache: Guidance? = null
    val guidance: Guidance?
        get() {
            val r = drives.getOrNull(chosen)
            if (r !== guidanceFor) { guidanceFor = r; guidanceCache = r?.let { Guidance(it) } }
            return guidanceCache
        }

    private var tts: TextToSpeech? = null
    private var navJob: Job? = null
    private var offCount = 0
    private var lastFix: Nav.Fix? = null
    private var mph = 0.0

    private fun bearingText(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val d = Cot.distanceMeters(a.first, a.second, b.first, b.second)
        val brg = Nav.bearingDeg(a.first, a.second, b.first, b.second)
        return "${Nav.distanceText(d)} at ${String.format(Locale.US, "%03d", brg)}° ${Nav.compass(brg)}"
    }

    /** Works out the route(s) from [start] (or the GPS position) to [dest]. */
    fun plan() {
        val to = dest ?: return
        val from = start ?: location.fix.value?.let { it.lat to it.lon }
        route = null; drives = emptyList(); chosen = 0
        if (from == null) { routeNote = "Destination set. No GPS position yet — long-press the map and choose \"Start here\"."; return }
        if (!store.covers(from.first, from.second)) { routeNote = "The start is off the map. Long-press the map and choose \"Start here\"."; return }
        if (drive) {
            if (!store.hasRoads(from.first, from.second)) {
                routeNote = "No driving roads on the maps here. Rebuild this area's map pack on the PC to add them."; return
            }
            routeNote = "Working out driving routes…"
            scope.launch {
                val rs = store.drive(from.first, from.second, to.first, to.second, blockedAt.toList(), vehicle = vehicle)
                drives = rs; planned++
                routeNote = if (rs.isNotEmpty()) "" else
                    "No driving route found: both ends must be within about 2 km of a road on one map pack, and " +
                        "the roads must connect" + (if (blockedAt.isNotEmpty()) " around the ${blockedAt.size} blocked road(s)" else "") +
                        ". Straight line: ${bearingText(from, to)}."
            }
            return
        }
        routeNote = "Working out the route…"
        scope.launch {
            val r = store.routeBetween(from.first, from.second, to.first, to.second)
            route = r; planned++
            routeNote = if (r != null) "" else
                "No walking route worked out: the two ends are not on one map with elevation, are too far apart " +
                    "(over about 40 km), or the ground between is too steep. Straight line: ${bearingText(from, to)}."
        }
    }

    fun clear() {
        endNav(); dest = null; start = null; route = null; drives = emptyList(); routeNote = ""; destName = ""
    }

    /** Starts turn-by-turn from where the phone is. */
    fun startNav() {
        if (start != null) { start = null; plan() }
        navState = null; offCount = 0; lastFix = null; mph = 0.0
        if (tts == null) tts = TextToSpeech(app) { ok -> if (ok == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        navigating = true
        location.fast(true)
        navJob?.cancel()
        navJob = scope.launch { location.fix.collect { f -> if (f != null) onFix(f) } }
    }

    fun endNav() {
        navigating = false
        navJob?.cancel(); navJob = null
        location.fast(false)
        tts?.stop()
    }

    fun toggleVoice() { voiceOn = !voiceOn; if (!voiceOn) tts?.stop() }

    /** Blocks the road 100 m ahead on the route and reroutes from here. */
    fun blockAhead() {
        val st = navState; val g = guidance
        val at = if (st != null && g != null) g.pointAhead(st.along, 100.0) else location.fix.value?.let { it.lat to it.lon }
        if (at != null) { blockedAt += at; start = null; plan() }
        say("Road blocked. Rerouting.")
    }

    private fun say(text: String) {
        if (voiceOn) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bastion-nav")
    }

    private fun onFix(f: Nav.Fix) {
        if (!navigating) return
        lastFix?.let { p ->
            val dt = (f.time - p.time) / 1000.0
            if (dt in 0.5..30.0) mph = Cot.distanceMeters(p.lat, p.lon, f.lat, f.lon) / dt / 0.44704
        }
        lastFix = f
        val g = guidance ?: return
        val s = g.update(f.lat, f.lon)
        navState = s
        val accurate = (f.accuracyM ?: 0f) < 50f
        offCount = if (accurate && s.offRoute > 60) offCount + 1 else 0
        if (offCount >= 3) {
            offCount = 0
            say("Rerouting.")
            start = null; plan()
            return
        }
        g.announcement(s, mph)?.let(::say)
        if (s.arrived) endNav()
    }

    companion object {
        @Volatile private var inst: RouteSession? = null
        fun get(context: Context): RouteSession =
            inst ?: synchronized(this) { inst ?: RouteSession(context.applicationContext).also { inst = it } }
    }
}
