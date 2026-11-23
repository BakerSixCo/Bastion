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
 * Map tab: an offline map drawn from a pack on the phone. Layers can be
 * switched on and off; a long press picks a place to route to or from.
 * Nothing here goes online — every tile comes out of the pack file.
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { MapPacks.get(ctx) }
    val packs by store.packs.collectAsState()
    val problems by store.problems.collectAsState()
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.rescan(); scanned = true }
    var chosen by rememberSaveable { mutableStateOf("") }

    if (packs.isEmpty()) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Map", style = MaterialTheme.typography.headlineMedium)
            if (scanned) {
                Text(
                    "No map on this phone yet. BASTION never goes online, so maps are made on a PC and copied over:\n\n" +
                        "1. On the PC run  tools\\map-build.ps1 -Name myarea -Center lat,lon -RadiusKm 10 -Push\n" +
                        "2. Come back here.\n\n" +
                        "A 20 km area is about 30 MB: topographic map, public land, Forest roads, trails, streams and elevation " +
                        "(for steepness, shading and walking routes).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                problems.forEach { Text("Could not open $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Folder: " + (store.folders().firstOrNull()?.path ?: "-"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val pack = packs.firstOrNull { it.name == chosen } ?: packs.first()
    MapView(pack, packs, onPick = { chosen = it }, modifier = modifier)
}

/** Raster layers the map can compute itself from the pack's elevation. */
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

/** Loads and keeps tile images; [version] changes whenever a new one is ready so the map redraws. */
private class TileImages(private val pack: MapPack, private val scope: CoroutineScope) {
    val version = mutableIntStateOf(0)
    private val cache = LruCache<String, ImageBitmap>(160)
    private val loading = HashSet<String>()
    private val absent = HashSet<String>()

    fun get(kind: String, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$kind/$z/$x/$y"
        cache.get(key)?.let { return it }
        if (key in absent || !loading.add(key)) return null
        scope.launch {
            val img = withContext(Dispatchers.Default) { runCatching { load(kind, z, x, y) }.getOrNull() }
            loading.remove(key)
            if (img != null) cache.put(key, img) else absent.add(key)
            version.intValue++
        }
        return null
    }

    private suspend fun load(kind: String, z: Int, x: Int, y: Int): ImageBitmap? {
        if (kind == SLOPE || kind == SHADE) {
            val dem = pack.dem() ?: return null
            val ox = x * Geo.TILE - dem.x0; val oy = y * Geo.TILE - dem.y0
            if (z != dem.zoom || ox < 0 || oy < 0 || ox + Geo.TILE > dem.w || oy + Geo.TILE > dem.h) return null
            val px = IntArray(Geo.TILE * Geo.TILE)
            for (j in 0 until Geo.TILE) for (i in 0 until Geo.TILE) {
                px[j * Geo.TILE + i] = if (kind == SLOPE) Dem.slopeColor(dem.slopeDeg(ox + i, oy + j)) else {
                    // Dark where the hill faces away from the light, clear where it is lit.
                    val s = dem.hillshade(ox + i, oy + j)
                    val a = ((0.75f - s).coerceIn(0f, 0.75f) * 255 * 0.9f).toInt()
                    a shl 24
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
private fun MapView(pack: MapPack, packs: List<MapPack>, onPick: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val text = rememberTextMeasurer()
    val images = remember(pack) { TileImages(pack, scope) }
    val prefs = remember { ctx.getSharedPreferences("map", android.content.Context.MODE_PRIVATE) }

    // View: centre as world fractions, and zoom (fractional).
    var cx by rememberSaveable(pack.name) { mutableStateOf((Geo.x(pack.west) + Geo.x(pack.east)) / 2) }
    var cy by rememberSaveable(pack.name) { mutableStateOf((Geo.y(pack.north) + Geo.y(pack.south)) / 2) }
    val minZoom = ((pack.topo?.minZoom ?: 9) - 1).toDouble()
    val maxZoom = ((pack.topo?.maxZoom ?: 15) + 2).toDouble()
    var zoom by rememberSaveable(pack.name) { mutableStateOf(12.0.coerceIn(minZoom, maxZoom)) }

    // Layers on/off (remembered between starts).
    val allLayers = remember(pack) {
        buildList {
            if (pack.land != null) add("land" to "Public land")
            pack.layers.forEach { add(it.id to it.title) }
            if (pack.elevZoom != null) { add(SLOPE to "Steepness"); add(SHADE to "Shaded relief") }
            add("team" to "Team positions")
        }
    }
    var on by remember(pack) {
        mutableStateOf(allLayers.map { it.first }.filter { prefs.getBoolean("layer_$it", it in setOf("roads", "trails", "mototrails", "team", SHADE)) }.toSet())
    }
    var showLayers by remember { mutableStateOf(false) }

    // Position and team.
    val location = remember { LocationSource.get(ctx) }
    val fix by location.fix.collectAsState()
    var hasGps by remember { mutableStateOf(location.hasPermission()) }
    val askGps = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { hasGps = it; if (it) location.start() }
    LaunchedEffect(hasGps) { if (hasGps) location.start() }
    val contacts by remember { LinkClient.get(ctx) }.contacts.collectAsState()

    // Routing.
    var picked by remember { mutableStateOf<Picked?>(null) }
    var start by remember(pack) { mutableStateOf<Pair<Double, Double>?>(null) }
    var dest by remember(pack) { mutableStateOf<Pair<Double, Double>?>(null) }
    var destName by remember(pack) { mutableStateOf("") }
    var route by remember(pack) { mutableStateOf<TerrainRoute.Route?>(null) }
    var routeNote by remember(pack) { mutableStateOf("") }
    var showGoTo by remember { mutableStateOf(false) }
    var showLegs by remember { mutableStateOf(false) }

    fun inPack(lat: Double, lon: Double) = pack.contains(lat, lon)

    fun plan() {
        val to = dest ?: return
        val from = start ?: fix?.let { it.lat to it.lon }
        route = null
        if (from == null) { routeNote = "Destination set. No GPS position yet — long-press the map and choose \"Start here\"."; return }
        if (!inPack(from.first, from.second)) { routeNote = "The start is outside this map. Long-press the map and choose \"Start here\"."; return }
        routeNote = "Working out the route…"
        scope.launch {
            val r = pack.route(from.first, from.second, to.first, to.second)
            route = r
            routeNote = if (r == null) {
                val d = com.bastion.terminal.link.Cot.distanceMeters(from.first, from.second, to.first, to.second)
                val b = Nav.bearingDeg(from.first, from.second, to.first, to.second)
                "No walkable route found on this map's ground. Straight line: ${Nav.distanceText(d)} at ${String.format(Locale.US, "%03d", b)}° ${Nav.compass(b)}."
            } else ""
            showLegs = false
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFFDDE3DA))) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                .pointerInput(pack) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val s0 = Geo.TILE * 2.0.pow(zoom) * density
                        // Keep the point under the fingers where it is while zooming.
                        val wx = cx + (centroid.x - size.width / 2) / s0
                        val wy = cy + (centroid.y - size.height / 2) / s0
                        zoom = (zoom + ln(gestureZoom.toDouble()) / ln(2.0)).coerceIn(minZoom, maxZoom)
                        val s1 = Geo.TILE * 2.0.pow(zoom) * density
                        cx = (wx - (centroid.x + pan.x - size.width / 2) / s1).coerceIn(Geo.x(pack.west), Geo.x(pack.east))
                        cy = (wy - (centroid.y + pan.y - size.height / 2) / s1).coerceIn(Geo.y(pack.north), Geo.y(pack.south))
                    }
                }
                .pointerInput(pack) {
                    detectTapGestures(onLongPress = { at ->
                        val s = Geo.TILE * 2.0.pow(zoom) * density
                        val lat = Geo.lat(cy + (at.y - size.height / 2) / s)
                        val lon = Geo.lon(cx + (at.x - size.width / 2) / s)
                        picked = Picked(lat, lon, null)
                        scope.launch { val e = pack.dem()?.elevation(lat, lon); picked?.let { if (it.lat == lat) picked = Picked(lat, lon, e) } }
                    })
                },
        ) {
            images.version.intValue // redraw when a tile arrives
            val s = Geo.TILE * 2.0.pow(zoom) * density
            fun sx(x: Double) = ((x - cx) * s + size.width / 2).toFloat()
            fun sy(y: Double) = ((y - cy) * s + size.height / 2).toFloat()

            fun raster(kind: String, minZ: Int, maxZ: Int, alpha: Float, smooth: Boolean) {
                // Use the tile level whose pixels are closest to the screen's.
                val z = floor(zoom + ln(density.toDouble()) / ln(2.0) + 0.3).toInt().coerceIn(minZ, maxZ)
                val n = Geo.tilesAcross(z)
                val tilePx = s / n
                val x0 = floor((cx - size.width / 2 / s) * n).toInt().coerceAtLeast(0)
                val x1 = floor((cx + size.width / 2 / s) * n).toInt().coerceAtMost(n - 1)
                val y0 = floor((cy - size.height / 2 / s) * n).toInt().coerceAtLeast(0)
                val y1 = floor((cy + size.height / 2 / s) * n).toInt().coerceAtMost(n - 1)
                if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 400) return
                for (ty in y0..y1) for (tx in x0..x1) {
                    val img = images.get(kind, z, tx, ty) ?: continue
                    val left = sx(tx.toDouble() / n); val top = sy(ty.toDouble() / n)
                    drawImage(
                        img,
                        dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt()),
                        dstSize = IntSize(ceil(tilePx).toInt() + 1, ceil(tilePx).toInt() + 1),
                        alpha = alpha,
                        filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None,
                    )
                }
            }

            pack.topo?.let { raster("topo", it.minZoom, it.maxZoom, 1f, true) }
            pack.elevZoom?.let { ez ->
                if (SHADE in on) raster(SHADE, ez, ez, 1f, true)
                if (SLOPE in on && zoom >= 11.5) raster(SLOPE, ez, ez, 0.6f, false)
            }
            pack.land?.let { if ("land" in on) raster("land", it.minZoom, it.maxZoom, 0.45f, true) }

            // Line and area layers.
            val vx0 = cx - size.width / 2 / s; val vx1 = cx + size.width / 2 / s
            val vy0 = cy - size.height / 2 / s; val vy1 = cy + size.height / 2 / s
            for (info in pack.layers) {
                if (info.id !in on) continue
                if (info.id == "water" && zoom < 12.5) continue // too dense to read when zoomed out
                val st = STYLES[info.id] ?: LayerStyle(Color.DarkGray, 2f)
                val path = Path()
                for (f in pack.features(info.id)) {
                    if (f.maxX < vx0 || f.minX > vx1 || f.maxY < vy0 || f.minY > vy1) continue
                    val xy = f.xy
                    path.moveTo(sx(xy[0]), sy(xy[1]))
                    var i = 2
                    while (i + 1 < xy.size) { path.lineTo(sx(xy[i]), sy(xy[i + 1])); i += 2 }
                    if (info.area) path.close()
                }
                st.fill?.let { drawPath(path, it) }
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
                    if (i == 0) return@forEachIndexed
                    marker(text, sx(Geo.x(leg.from.lon)), sy(Geo.y(leg.from.lat)), Color(0xFF0D47A1), "$i", density)
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

            // This phone.
            fix?.let { f ->
                val x = sx(Geo.x(f.lon)); val y = sy(Geo.y(f.lat))
                f.accuracyM?.let { acc ->
                    val r = (acc / (Geo.metersPerPixel(f.lat, 0) * Geo.TILE / s)).toFloat()
                    if (r > 9 * density) drawCircle(Color(0x332962FF), r, Offset(x, y))
                }
                drawCircle(Color.White, 9f * density, Offset(x, y))
                drawCircle(Color(0xFF2962FF), 6.5f * density, Offset(x, y))
            }

            // Scale bar, bottom left.
            val mpp = Geo.metersPerPixel(Geo.lat(cy), 0) * Geo.TILE / s
            val nice = listOf(50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000).lastOrNull { it / mpp <= size.width * 0.3 } ?: 50
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
                else if (f != null && inPack(f.lat, f.lon)) { cx = Geo.x(f.lon); cy = Geo.y(f.lat); if (zoom < 14) zoom = 14.0 }
                else routeNote = if (f == null) "No GPS position yet. Outdoors it can take a minute." else "You are outside this map."
            }) { Text("Me") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { zoom = (zoom - 1).coerceAtLeast(minZoom) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−") }
                FilledTonalButton(onClick = { zoom = (zoom + 1).coerceAtMost(maxZoom) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+") }
            }
        }

        // Pack name / picker, top left.
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            if (packs.size > 1) {
                var open by remember { mutableStateOf(false) }
                FilledTonalButton(onClick = { open = true }) { Text(pack.name) }
                if (open) AlertDialog(
                    onDismissRequest = { open = false },
                    confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
                    title = { Text("Maps on this phone") },
                    text = { Column { packs.forEach { p -> TextButton(onClick = { onPick(p.name); open = false }) { Text(p.name + "  (built ${p.built})") } } } },
                )
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
                    fix?.let { f ->
                        val d = com.bastion.terminal.link.Cot.distanceMeters(f.lat, f.lon, p.lat, p.lon)
                        val b = Nav.bearingDeg(f.lat, f.lon, p.lat, p.lon)
                        Text("From you: ${Nav.distanceText(d)} at ${String.format(Locale.US, "%03d", b)}° ${Nav.compass(b)} (straight line)")
                    }
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
                Text(
                    "Map \"${pack.name}\", built ${pack.built}. ${pack.credits}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
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
                        val inside = inPack(c.lat, c.lon)
                        TextButton(enabled = inside, onClick = {
                            dest = c.lat to c.lon; destName = c.callsign.substringBefore(" - "); showGoTo = false
                            cx = Geo.x(c.lon); cy = Geo.y(c.lat); plan()
                        }) { Text(c.callsign.substringBefore(" - ") + (if (inside) "" else "  (outside this map)")) }
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
