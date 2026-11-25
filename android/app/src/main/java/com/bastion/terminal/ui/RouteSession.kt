package com.bastion.terminal.ui

import android.content.Context
import android.os.SystemClock
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
    /** Counts plan requests: a search result is used only if no newer request (or Clear) came after it. */
    private var request = 0
    /** Navigating, but no current GPS fix to plan from yet: plan on the next one. */
    private var planOnFix = false

    /** A fix is current enough to navigate by (the cached last position may be far behind). */
    private fun isFresh(f: Nav.Fix) = Guidance.fresh(f.elapsedMs, SystemClock.elapsedRealtime())

    private fun bearingText(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val d = Cot.distanceMeters(a.first, a.second, b.first, b.second)
        val brg = Nav.bearingDeg(a.first, a.second, b.first, b.second)
        return "${Nav.distanceText(d)} at ${String.format(Locale.US, "%03d", brg)}° ${Nav.compass(brg)}"
    }

    /** Works out the route(s) from [start] (or the GPS position) to [dest]. */
    fun plan() {
        val req = ++request
        val to = dest ?: return
        // While navigating, only a current fix counts as "here".
        val gps = location.fix.value?.takeIf { !navigating || isFresh(it) }
        val from = start ?: gps?.let { it.lat to it.lon }
        route = null; drives = emptyList(); chosen = 0
        navState = null   // never leave the old instruction up while a new route is worked out
        if (from == null) {
            if (navigating) { planOnFix = true; routeNote = "Waiting for a current GPS position…"; return }
            routeNote = "Destination set. No GPS position yet — long-press the map and choose \"Start here\"."; return
        }
        if (!store.covers(from.first, from.second)) {
            routeNote = "The start is off the map. Long-press the map and choose \"Start here\"."; navFailed(); return
        }
        if (drive) {
            if (!store.hasRoads(from.first, from.second)) {
                routeNote = "No driving roads on the maps here. Rebuild this area's map pack on the PC to add them."; navFailed(); return
            }
            routeNote = "Working out driving routes…"
            scope.launch {
                val rs = store.drive(from.first, from.second, to.first, to.second, blockedAt.toList(), vehicle = vehicle)
                if (req != request) return@launch   // cleared or replaced while searching
                drives = rs; planned++
                routeNote = if (rs.isNotEmpty()) "" else
                    "No driving route found: both ends must be within about 2 km of a road on one map pack, and " +
                        "the roads must connect" + (if (blockedAt.isNotEmpty()) " around the ${blockedAt.size} blocked road(s)" else "") +
                        ". Straight line: ${bearingText(from, to)}."
                if (rs.isEmpty()) navFailed()
            }
            return
        }
        routeNote = "Working out the route…"
        scope.launch {
            val r = store.routeBetween(from.first, from.second, to.first, to.second)
            if (req != request) return@launch
            route = r; planned++
            routeNote = if (r != null) "" else
                "No walking route worked out: the two ends are not on one map with elevation, are too far apart " +
                    "(over about 40 km), or the ground between is too steep. Straight line: ${bearingText(from, to)}."
        }
    }

    fun clear() {
        request++   // any search still running is for the destination being removed
        endNav(); dest = null; start = null; route = null; drives = emptyList(); routeNote = ""; destName = ""
    }

    /** Starts turn-by-turn from where the phone is. */
    fun startNav() {
        navState = null; offCount = 0; lastFix = null; mph = 0.0; planOnFix = false
        if (tts == null) tts = TextToSpeech(app) { ok -> if (ok == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        navigating = true
        location.fast(true)
        // Planned from a chosen start: plan again from here (on a current fix; plan() waits for one).
        if (start != null) { start = null; plan() }
        navJob?.cancel()
        navJob = scope.launch { location.fix.collect { f -> if (f != null) onFix(f) } }
    }

    /** [stopSpeech] false lets the last words ("You have arrived.") finish. */
    fun endNav(stopSpeech: Boolean = true) {
        navigating = false; planOnFix = false
        navJob?.cancel(); navJob = null
        location.fast(false)
        if (stopSpeech) tts?.stop()
    }

    /** Navigation cannot go on (no route from here): stop it, release fast GPS; the route card shows [routeNote]. */
    private fun navFailed() {
        if (!navigating) return
        endNav()
        say("No route found. Navigation stopped.")
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
        // An old fix (such as the cached last position) must not start, steer or end navigation.
        if (!isFresh(f)) return
        lastFix?.let { p ->
            val dt = (f.time - p.time) / 1000.0
            if (dt in 0.5..30.0) mph = Cot.distanceMeters(p.lat, p.lon, f.lat, f.lon) / dt / 0.44704
        }
        lastFix = f
        if (planOnFix) { planOnFix = false; plan(); return }
        val g = guidance ?: return
        val acc = f.accuracyM
        val s = g.update(f.lat, f.lon, acc)
        navState = s
        val accurate = acc != null && acc < 50f   // unknown accuracy is not good accuracy
        offCount = if (accurate && s.offRoute > Guidance.OFF_ROUTE_M) offCount + 1 else 0
        if (offCount >= 3) {
            offCount = 0
            say("Rerouting.")
            start = null; plan()
            return
        }
        g.announcement(s, mph)?.let(::say)
        if (s.arrived) endNav(stopSpeech = false)
    }

    companion object {
        /** Shown with a route whose road data has gaps ([RoadGraph.Route.reducedCoverage]): never hidden. */
        const val COVERAGE_NOTE = "Part of the road data for this area is missing or could not be read: roads or turn " +
            "restrictions may be missing from this route. Follow road signs."

        @Volatile private var inst: RouteSession? = null
        fun get(context: Context): RouteSession =
            inst ?: synchronized(this) { inst ?: RouteSession(context.applicationContext).also { inst = it } }
    }
}
