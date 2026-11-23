package com.bastion.terminal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bastion.terminal.link.Cot
import com.bastion.terminal.link.LinkClient
import com.bastion.terminal.link.TakContact
import com.bastion.terminal.map.Dem
import com.bastion.terminal.map.Geo
import com.bastion.terminal.map.MapPack
import com.bastion.terminal.map.MapPacks
import com.bastion.terminal.map.TerrainRoute
import com.bastion.terminal.nav.LocationSource
import com.bastion.terminal.nav.Nav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Map tab: an offline map drawn from the packs on the phone, all of them
 * together as one map (a country overview under state maps under detailed
 * local ones). Layers can be switched on and off; a long press picks a place
 * to route to or from. Nothing here goes online — every tile comes out of a
 * pack file.
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { MapPacks.get(ctx) }
    val packs by store.packs.collectAsState()
    val problems by store.problems.collectAsState()
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.rescan(); scanned = true }

    if (packs.isEmpty()) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Map", style = MaterialTheme.typography.headlineMedium)
            if (scanned) {
                Text(
                    "No map on this phone yet. BASTION never goes online, so maps are made on a PC and copied over:\n\n" +
                        "1. On the PC run one of\n" +
                        "   tools\\map-build.ps1 -Name myarea -Center lat,lon -RadiusKm 10 -Push\n" +
                        "   tools\\map-build.ps1 -State ID -Push -Sd\n" +
                        "2. Come back here.\n\n" +
                        "A 20 km area in full detail is about 20 MB. A whole state is a few GB and belongs on the SD card. " +
                        "Each map has the topographic map, public land, Forest roads, trails and elevation " +
                        "(for steepness, shading and walking routes). Several maps show together as one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                problems.forEach { Text("Could not open $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Folder: " + (store.folders().firstOrNull()?.path ?: "-"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    MapView(store, packs, modifier)
}

/** Raster layers the map computes itself from a pack's elevation. */
private const val SLOPE = "slope"
private const val SHADE = "shade"

private data class LayerStyle(val color: Color, val width: Float, val dash: Boolean = false, val fill: Color? = null)

private val STYLES = mapOf(
    "roads" to LayerStyle(Color(0xFFB34700), 3f),
    "mototrails" to LayerStyle(Color(0xFF7B1FA2), 2.5f, dash = true),
    "trails" to LayerStyle(Color(0xFFD50000), 2.5f, dash = true),
    "water" to LayerStyle(Color(0xFF1565C0), 1.6f),
    "wilderness" to LayerStyle(Color(0xFF1B5E20), 3f, fill = Color(0x221B5E20)),
)

/**
 * Loads and keeps what the map draws — tile images and line-layer cells —
 * off the main thread; [version] changes whenever something new is ready so
 * the map redraws. Only what is on screen is ever read from a pack.
 */
private class MapData(private val scope: CoroutineScope) {
    val version = mutableIntStateOf(0)
    private val images = LruCache<String, ImageBitmap>(200)
    private val cells = LruCache<String, List<MapPack.Feature>>(60)
    private val loading = HashSet<String>()
    private val absent = HashSet<String>()

    private fun <T : Any> fetch(key: String, cache: LruCache<String, T>, load: () -> T?): T? {
        cache.get(key)?.let { return it }
        if (key in absent || !loading.add(key)) return null
        scope.launch {
            val v = withContext(Dispatchers.Default) { runCatching { load() }.getOrNull() }
            loading.remove(key)
            if (v != null) cache.put(key, v) else absent.add(key)
            version.intValue++
        }
        return null
    }

    fun image(pack: MapPack, kind: String, z: Int, x: Int, y: Int): ImageBitmap? =
        fetch("${pack.name}/$kind/$z/$x/$y", images) { render(pack, kind, z, x, y) }

    fun cell(pack: MapPack, id: String, x: Int, y: Int): List<MapPack.Feature>? =
        fetch("${pack.name}/$id/$x/$y", cells) { pack.cell(id, x, y) }

    private fun render(pack: MapPack, kind: String, z: Int, x: Int, y: Int): ImageBitmap? {
        if (kind == SLOPE || kind == SHADE) {
            // The tile and its neighbours, so slopes are right across tile edges.
            val dem = pack.demTiles(x - 1, y - 1, x + 1, y + 1) ?: return null
            val ox = x * Geo.TILE - dem.x0; val oy = y * Geo.TILE - dem.y0
            if (z != dem.zoom || ox < 0 || oy < 0 || ox + Geo.TILE > dem.w || oy + Geo.TILE > dem.h) return null
            val px = IntArray(Geo.TILE * Geo.TILE)
            for (j in 0 until Geo.TILE) for (i in 0 until Geo.TILE) {
                px[j * Geo.TILE + i] = if (kind == SLOPE) Dem.slopeColor(dem.slopeDeg(ox + i, oy + j)) else {
                    // Dark where the hill faces away from the light, clear where it is lit.
                    val s = dem.hillshade(ox + i, oy + j)
                    ((0.75f - s).coerceIn(0f, 0.75f) * 255 * 0.9f).toInt() shl 24
                }
            }
            return Bitmap.createBitmap(px, Geo.TILE, Geo.TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        val data = pack.tile(kind, z, x, y) ?: return null
        return BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
    }
}

private class Picked(val lat: Double, val lon: Double, val elevation: Float?)

@Composable
private fun MapView(store: MapPacks, packs: List<MapPack>, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val text = rememberTextMeasurer()
    val data = remember(packs) { MapData(scope) }
    val prefs = remember { ctx.getSharedPreferences("map", android.content.Context.MODE_PRIVATE) }

    // Position and team.
    val location = remember { LocationSource.get(ctx) }
    val fix by location.fix.collectAsState()
    var hasGps by remember { mutableStateOf(location.hasPermission()) }
    val askGps = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { hasGps = it; if (it) location.start() }
    LaunchedEffect(hasGps) { if (hasGps) location.start() }
    val contacts by remember { LinkClient.get(ctx) }.contacts.collectAsState()

    // What the packs cover together.
    val west = packs.minOf { it.west }; val east = packs.maxOf { it.east }
    val south = packs.minOf { it.south }; val north = packs.maxOf { it.north }
    val minZoom = (packs.minOf { it.topo?.minZoom ?: 9 } - 1).toDouble()
    val maxZoom = (packs.maxOf { it.topo?.maxZoom ?: 13 } + 2).toDouble()

    // View: centre as world fractions, and zoom (fractional). Opens on this phone if it is on
    // the map, otherwise on the most detailed map there is.
    val home = remember(packs) {
        val f = fix
        if (f != null && store.covers(f.lat, f.lon)) Triple(Geo.x(f.lon), Geo.y(f.lat), 13.0) else packs.last().let { p ->
            Triple((Geo.x(p.west) + Geo.x(p.east)) / 2, (Geo.y(p.north) + Geo.y(p.south)) / 2, ((p.topo?.maxZoom ?: 13) - 3).toDouble())
        }
    }
    var cx by rememberSaveable { mutableStateOf(home.first) }
    var cy by rememberSaveable { mutableStateOf(home.second) }
    var zoom by rememberSaveable { mutableStateOf(home.third.coerceIn(minZoom, maxZoom)) }

    // Layers on/off (remembered between starts).
    val allLayers = remember(packs) {
        buildList {
            if (packs.any { it.land != null }) add("land" to "Public land")
            packs.flatMap { it.layers }.distinctBy { it.id }.forEach { add(it.id to it.title) }
            if (packs.any { it.elevZoom != null }) { add(SLOPE to "Steepness"); add(SHADE to "Shaded relief") }
            add("team" to "Team positions")
        }
    }
    var on by remember(packs) {
        mutableStateOf(allLayers.map { it.first }.filter { prefs.getBoolean("layer_$it", it in setOf("roads", "trails", "mototrails", "team", SHADE)) }.toSet())
    }
    var showLayers by remember { mutableStateOf(false) }

    // Routing.
    var picked by remember { mutableStateOf<Picked?>(null) }
    var start by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var dest by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var destName by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<TerrainRoute.Route?>(null) }
    var routeNote by remember { mutableStateOf("") }
    var showGoTo by remember { mutableStateOf(false) }
    var showLegs by remember { mutableStateOf(false) }

    fun bearingText(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val d = Cot.distanceMeters(a.first, a.second, b.first, b.second)
        val brg = Nav.bearingDeg(a.first, a.second, b.first, b.second)
        return "${Nav.distanceText(d)} at ${String.format(Locale.US, "%03d", brg)}° ${Nav.compass(brg)}"
    }

    fun plan() {
        val to = dest ?: return
        val from = start ?: fix?.let { it.lat to it.lon }
        route = null
        if (from == null) { routeNote = "Destination set. No GPS position yet — long-press the map and choose \"Start here\"."; return }
        if (!store.covers(from.first, from.second)) { routeNote = "The start is off the map. Long-press the map and choose \"Start here\"."; return }
        routeNote = "Working out the route…"
        scope.launch {
            val r = store.routeBetween(from.first, from.second, to.first, to.second)
            route = r
            routeNote = if (r != null) "" else
                "No walking route worked out: the two ends are not on one map with elevation, are too far apart " +
                    "(over about 40 km), or the ground between is too steep. Straight line: ${bearingText(from, to)}."
            showLegs = false
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFFDDE3DA))) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                .pointerInput(packs) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val s0 = Geo.TILE * 2.0.pow(zoom) * density
                        // Keep the point under the fingers where it is while zooming.
                        val wx = cx + (centroid.x - size.width / 2) / s0
                        val wy = cy + (centroid.y - size.height / 2) / s0
                        zoom = (zoom + ln(gestureZoom.toDouble()) / ln(2.0)).coerceIn(minZoom, maxZoom)
                        val s1 = Geo.TILE * 2.0.pow(zoom) * density
                        cx = (wx - (centroid.x + pan.x - size.width / 2) / s1).coerceIn(Geo.x(west), Geo.x(east))
                        cy = (wy - (centroid.y + pan.y - size.height / 2) / s1).coerceIn(Geo.y(north), Geo.y(south))
                    }
                }
                .pointerInput(packs) {
                    detectTapGestures(onLongPress = { at ->
                        val s = Geo.TILE * 2.0.pow(zoom) * density
                        val lat = Geo.lat(cy + (at.y - size.height / 2) / s)
                        val lon = Geo.lon(cx + (at.x - size.width / 2) / s)
                        picked = Picked(lat, lon, null)
                        scope.launch { val e = store.elevation(lat, lon); picked?.let { if (it.lat == lat) picked = Picked(lat, lon, e) } }
                    })
                },
        ) {
            data.version.intValue // redraw when a tile or layer cell arrives
            val s = Geo.TILE * 2.0.pow(zoom) * density
            fun sx(x: Double) = ((x - cx) * s + size.width / 2).toFloat()
            fun sy(y: Double) = ((y - cy) * s + size.height / 2).toFloat()
            // The view in world fractions.
            val vx0 = cx - size.width / 2 / s; val vx1 = cx + size.width / 2 / s
            val vy0 = cy - size.height / 2 / s; val vy1 = cy + size.height / 2 / s
            fun onScreen(p: MapPack) = Geo.x(p.east) >= vx0 && Geo.x(p.west) <= vx1 && Geo.y(p.south) >= vy0 && Geo.y(p.north) <= vy1
            // The tile level whose pixels are closest to the screen's.
            val want = floor(zoom + ln(density.toDouble()) / ln(2.0) + 0.3).toInt()

            fun raster(pack: MapPack, kind: String, minZ: Int, maxZ: Int, alpha: Float, smooth: Boolean) {
                if (want < minZ - 2) return // far too zoomed out for this pack: a coarser one covers it
                val z = want.coerceIn(minZ, maxZ)
                val n = Geo.tilesAcross(z)
                val tilePx = s / n
                // Only the tiles this pack has: inside its own bounds.
                val x0 = maxOf(floor(vx0 * n).toInt(), Geo.tileX(pack.west, z)); val x1 = minOf(floor(vx1 * n).toInt(), Geo.tileX(pack.east, z))
                val y0 = maxOf(floor(vy0 * n).toInt(), Geo.tileY(pack.north, z)); val y1 = minOf(floor(vy1 * n).toInt(), Geo.tileY(pack.south, z))
                if (x0 > x1 || y0 > y1 || (x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 300) return
                for (ty in y0..y1) for (tx in x0..x1) {
                    val img = data.image(pack, kind, z, tx, ty) ?: continue
                    drawImage(
                        img,
                        dstOffset = IntOffset(floor(sx(tx.toDouble() / n)).toInt(), floor(sy(ty.toDouble() / n)).toInt()),
                        dstSize = IntSize(ceil(tilePx).toInt() + 1, ceil(tilePx).toInt() + 1),
                        alpha = alpha,
                        filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None,
                    )
                }
            }

            // Packs are ordered coarsest first, so detailed maps are drawn over coarse ones.
            val visible = packs.filter(::onScreen)
            visible.forEach { p -> p.topo?.let { raster(p, "topo", it.minZoom, it.maxZoom, 1f, true) } }
            // Computed layers come from the most detailed elevation at the centre only (no double shading).
            val withElev = visible.filter { it.elevZoom != null }
            val here = withElev.filter { it.contains(Geo.lat(cy), Geo.lon(cx)) }.maxByOrNull { it.elevZoom!! * 1000.0 - it.areaKm2 / 1e7 }
            (if (here != null) listOf(here) else withElev).forEach { p ->
                val ez = p.elevZoom!!
                if (SHADE in on && want >= ez - 2) raster(p, SHADE, ez, ez, 1f, true)
                if (SLOPE in on && want >= ez - 1) raster(p, SLOPE, ez, ez, 0.6f, false)
            }
            if ("land" in on) visible.forEach { p -> p.land?.let { raster(p, "land", it.minZoom, it.maxZoom, 0.45f, true) } }

            // Line and area layers, read cell by cell as they come into view.
            for ((id, _) in allLayers) {
                if (id !in on) continue
                val st = STYLES[id] ?: continue
                if (id == "water" && zoom < 12.5) continue // too dense to read when zoomed out
                val path = Path()
                var area = false
                for (p in visible) {
                    val info = p.layers.firstOrNull { it.id == id } ?: continue
                    area = info.area
                    val lists = if (info.grid == null) listOfNotNull(data.cell(p, id, 0, 0)) else {
                        if (zoom < info.grid + 0.5) continue // a line layer over a whole region is unreadable and slow
                        val k = Geo.tilesAcross(info.grid)
                        val c0 = maxOf(floor(vx0 * k).toInt(), Geo.tileX(p.west, info.grid)); val c1 = minOf(floor(vx1 * k).toInt(), Geo.tileX(p.east, info.grid))
                        val r0 = maxOf(floor(vy0 * k).toInt(), Geo.tileY(p.north, info.grid)); val r1 = minOf(floor(vy1 * k).toInt(), Geo.tileY(p.south, info.grid))
                        if (c0 > c1 || r0 > r1 || (c1 - c0 + 1) * (r1 - r0 + 1) > 16) continue
                        (c0..c1).flatMap { c -> (r0..r1).mapNotNull { r -> data.cell(p, id, c, r) } }
                    }
                    for (list in lists) for (f in list) {
                        if (f.maxX < vx0 || f.minX > vx1 || f.maxY < vy0 || f.minY > vy1) continue
                        val xy = f.xy
                        path.moveTo(sx(xy[0]), sy(xy[1]))
                        var i = 2
                        while (i + 1 < xy.size) { path.lineTo(sx(xy[i]), sy(xy[i + 1])); i += 2 }
                        if (info.area) path.close()
                    }
                }
                if (area) st.fill?.let { drawPath(path, it) }
                drawPath(
                    path, st.color,
                    style = Stroke(
                        width = st.width * density,
                        pathEffect = if (st.dash) PathEffect.dashPathEffect(floatArrayOf(8f * density, 5f * density)) else null,
                    ),
                )
            }

            // Route.
            route?.let { r ->
                val p = Path()
                r.path.forEachIndexed { i, pt ->
                    val x = sx(Geo.x(pt.lon)); val y = sy(Geo.y(pt.lat))
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, Color.White, style = Stroke(width = 7f * density))
                drawPath(p, Color(0xFF0D47A1), style = Stroke(width = 4f * density))
                r.legs.forEachIndexed { i, leg ->
                    if (i > 0) marker(text, sx(Geo.x(leg.from.lon)), sy(Geo.y(leg.from.lat)), Color(0xFF0D47A1), "$i", density)
                }
            }
            start?.let { (la, lo) -> marker(text, sx(Geo.x(lo)), sy(Geo.y(la)), Color(0xFF2E7D32), "S", density) }
            dest?.let { (la, lo) -> marker(text, sx(Geo.x(lo)), sy(Geo.y(la)), Color(0xFFC62828), "X", density) }

            // Team.
            if ("team" in on) contacts.filter { it.hasPosition }.forEach { c ->
                val x = sx(Geo.x(c.lon)); val y = sy(Geo.y(c.lat))
                if (x < -50 || y < -50 || x > size.width + 50 || y > size.height + 50) return@forEach
                drawCircle(Color.White, 8f * density, Offset(x, y))
                drawCircle(Color(0xFFEF6C00), 6f * density, Offset(x, y))
                label(text, c.callsign.substringBefore(" - "), x + 10 * density, y - 8 * density)
            }

            // Ground metres per screen pixel here.
            val mpp = Geo.metersPerPixel(Geo.lat(cy), 0) * Geo.TILE / s

            // This phone.
            fix?.let { f ->
                val x = sx(Geo.x(f.lon)); val y = sy(Geo.y(f.lat))
                f.accuracyM?.let { acc ->
                    val r = (acc / mpp).toFloat()
                    if (r > 9 * density) drawCircle(Color(0x332962FF), r, Offset(x, y))
                }
                drawCircle(Color.White, 9f * density, Offset(x, y))
                drawCircle(Color(0xFF2962FF), 6.5f * density, Offset(x, y))
            }

            // Scale bar, bottom left.
            val nice = listOf(50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000)
                .lastOrNull { it / mpp <= size.width * 0.3 } ?: 50
            val barPx = (nice / mpp).toFloat()
            val by = size.height - 14 * density; val bx = 12 * density
            drawLine(Color.White, Offset(bx, by), Offset(bx + barPx, by), strokeWidth = 5f * density)
            drawLine(Color.Black, Offset(bx, by), Offset(bx + barPx, by), strokeWidth = 2.5f * density)
            label(text, if (nice >= 1000) "${nice / 1000} km" else "$nice m", bx, by - 20 * density)
        }

        // Buttons, top right.
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
            FilledTonalButton(onClick = { showLayers = true }) { Text("Layers") }
            FilledTonalButton(onClick = { showGoTo = true }) { Text("Go to") }
            FilledTonalButton(onClick = {
                val f = fix
                if (!hasGps) askGps.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                else if (f != null && store.covers(f.lat, f.lon)) { cx = Geo.x(f.lon); cy = Geo.y(f.lat); if (zoom < 14) zoom = 14.0.coerceAtMost(maxZoom) }
                else routeNote = if (f == null) "No GPS position yet. Outdoors it can take a minute." else "You are off the maps on this phone."
            }) { Text("Me") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { zoom = (zoom - 1).coerceAtLeast(minZoom) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
                FilledTonalButton(onClick = { zoom = (zoom + 1).coerceAtMost(maxZoom) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
            }
        }

        // Route card, bottom.
        if (dest != null || routeNote.isNotBlank()) {
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 34.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    route?.let { r ->
                        Text((if (destName.isNotBlank()) "To $destName: " else "Route: ") + r.summary(), style = MaterialTheme.typography.titleSmall)
                        if (showLegs) Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
                            r.legs.forEachIndexed { i, leg -> Text(leg.text(i + 1), style = MaterialTheme.typography.bodyMedium) }
                            Text(
                                "Worked out from slope and the trails on this map. It does not know rivers, small cliffs, deadfall, snow, " +
                                    "private land or closures — check the ground.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (routeNote.isNotBlank()) Text(routeNote, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (route != null) TextButton(onClick = { showLegs = !showLegs }) { Text(if (showLegs) "Hide steps" else "Show steps") }
                        TextButton(onClick = { dest = null; start = null; route = null; routeNote = ""; destName = "" }) { Text("Clear") }
                    }
                }
            }
        }
    }

    picked?.let { p ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    p.elevation?.let { Text("Elevation ${it.roundToInt()} m (${(it * 3.28084f).roundToInt()} ft)") }
                    fix?.let { f -> Text("From you: ${bearingText(f.lat to f.lon, p.lat to p.lon)} (straight line)") }
                    Button(onClick = { dest = p.lat to p.lon; destName = ""; picked = null; plan() }, Modifier.fillMaxWidth()) { Text("Route to here") }
                    OutlinedButton(onClick = { start = p.lat to p.lon; picked = null; if (dest != null) plan() else routeNote = "Start set. Long-press where you want to go." }, Modifier.fillMaxWidth()) { Text("Start here") }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }

    if (showLayers) AlertDialog(
        onDismissRequest = { showLayers = false },
        confirmButton = { TextButton(onClick = { showLayers = false }) { Text("Done") } },
        title = { Text("Layers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                allLayers.forEach { (id, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f))
                        Switch(checked = id in on, onCheckedChange = { v ->
                            on = if (v) on + id else on - id
                            prefs.edit().putBoolean("layer_$id", v).apply()
                        })
                    }
                    if (id == SLOPE && SLOPE in on) Dem.SLOPE_CLASSES.drop(1).forEach { (_, argb, what) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                            Box(Modifier.size(14.dp).background(Color(argb or (0xFF shl 24))))
                            Text("  $what", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Maps on this phone (${packs.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                packs.asReversed().forEach { p ->
                    TextButton(onClick = {
                        cx = (Geo.x(p.west) + Geo.x(p.east)) / 2; cy = (Geo.y(p.north) + Geo.y(p.south)) / 2
                        zoom = ((p.topo?.maxZoom ?: 13) - 3).toDouble().coerceIn(minZoom, maxZoom); showLayers = false
                    }) { Text("${p.name} — detail to zoom ${p.topo?.maxZoom ?: "-"}, built ${p.built}") }
                }
                Text(
                    packs.last().credits + " Lines (roads, trails) appear when zoomed in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
    )

    if (showGoTo) {
        val units = contacts.filter { it.hasPosition }
        AlertDialog(
            onDismissRequest = { showGoTo = false },
            confirmButton = { TextButton(onClick = { showGoTo = false }) { Text("Close") } },
            title = { Text("Go to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Or long-press anywhere on the map.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (units.isEmpty()) Text("No team positions known yet (they come from the TAK link on the Mesh tab).")
                    units.forEach { c: TakContact ->
                        val inside = store.covers(c.lat, c.lon)
                        TextButton(enabled = inside, onClick = {
                            dest = c.lat to c.lon; destName = c.callsign.substringBefore(" - "); showGoTo = false
                            cx = Geo.x(c.lon); cy = Geo.y(c.lat); plan()
                        }) { Text(c.callsign.substringBefore(" - ") + (if (inside) "" else "  (off the map)")) }
                    }
                }
            },
        )
    }
}

private fun DrawScope.marker(text: TextMeasurer, x: Float, y: Float, color: Color, letter: String, density: Float) {
    drawCircle(Color.White, 11f * density, Offset(x, y))
    drawCircle(color, 9f * density, Offset(x, y))
    val m = text.measure(letter, TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
    drawText(m, topLeft = Offset(x - m.size.width / 2f, y - m.size.height / 2f))
}

private fun DrawScope.label(text: TextMeasurer, s: String, x: Float, y: Float) {
    val m = text.measure(s, TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, background = Color(0xCCFFFFFF)))
    if (x < 0 || y < 0 || x + m.size.width > size.width || y + m.size.height > size.height) return
    drawText(m, topLeft = Offset(x, y))
}
