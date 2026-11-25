package com.bastion.terminal.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.max

/**
 * One offline map pack (`.bmap`, built on a PC by `tools/map-build.ps1`): a
 * zip holding map tiles, elevation tiles and line/area layers for one area —
 * a valley, a state, or a low-detail overview of the whole country. Several
 * packs on the phone are drawn together as one map. Read-only; BASTION never
 * downloads map data itself.
 *
 * A pack can be far larger than memory, so nothing is loaded whole: elevation
 * is read for the window that is needed ([dem]) and line layers are stored in
 * grid cells and read cell by cell ([cell]).
 *
 * Everything in a pack is **data to draw**, never instructions.
 */
class MapPack private constructor(val file: File, private val zip: ZipFile, private val entries: Map<String, ZipEntry>, json: JSONObject) {

    data class Raster(val minZoom: Int, val maxZoom: Int, val ext: String)

    /** [grid]: the zoom of the cells a line layer is cut into, or null if it is one file ([path]). */
    data class LayerInfo(val id: String, val title: String, val area: Boolean, val path: String, val grid: Int?, val point: Boolean = false)

    /** A line or outline: world-fraction x,y pairs, with its bounds for quick skipping. */
    class Feature(val name: String, val detail: String, val xy: DoubleArray, val cat: String = "") {
        val minX: Double; val maxX: Double; val minY: Double; val maxY: Double
        init {
            var a = 1.0; var b = 0.0; var c = 1.0; var d = 0.0
            var i = 0
            while (i + 1 < xy.size) {
                if (xy[i] < a) a = xy[i]; if (xy[i] > b) b = xy[i]
                if (xy[i + 1] < c) c = xy[i + 1]; if (xy[i + 1] > d) d = xy[i + 1]
                i += 2
            }
            minX = a; maxX = b; minY = c; maxY = d
        }
    }

    val name: String = json.optString("name", file.nameWithoutExtension)
    val built: String = json.optString("built")
    val credits: String = json.optString("credits")
    val west: Double; val south: Double; val east: Double; val north: Double
    val topo: Raster? = raster(json.optJSONObject("topo"))
    val land: Raster? = raster(json.optJSONObject("land"))
    /** Satellite imagery, in packs built with -Satellite. */
    val sat: Raster? = raster(json.optJSONObject("sat"))
    val elevZoom: Int? = json.optJSONObject("elev")?.optInt("zoom")
    private val elevExt = json.optJSONObject("elev")?.optString("ext", "png") ?: "png"
    val layers: List<LayerInfo>

    /** Grid zoom of the driving-road network (layers/streets/x/y.bin), or null if the pack has none. */
    val roadGrid: Int?

    /** Rough ground area, km² — used to prefer the most local pack. */
    val areaKm2: Double

    init {
        val b = json.getJSONArray("bbox")
        west = b.getDouble(0); south = b.getDouble(1); east = b.getDouble(2); north = b.getDouble(3)
        areaKm2 = (east - west) * 111.32 * cos(Math.toRadians((south + north) / 2)) * (north - south) * 111.32
        val arr = json.optJSONArray("layers") ?: JSONArray()
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("count", 1) > 0 }
        roadGrid = all.firstOrNull { it.optString("kind") == "roadgraph" && it.has("grid") }?.getInt("grid")
        layers = all.filter { it.optString("kind") != "roadgraph" }.map {
            LayerInfo(
                it.getString("id"), it.optString("title", it.getString("id")), it.optString("kind") == "area",
                it.optString("file"), if (it.has("grid")) it.getInt("grid") else null, it.optString("kind") == "point",
            )
        }.filter { it.grid != null || it.path in entries }
    }

    private fun raster(o: JSONObject?): Raster? =
        o?.let { Raster(it.getInt("minZoom"), it.getInt("maxZoom"), it.optString("ext", "png")) }

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east

    private fun bytes(path: String): ByteArray? {
        val e = entries[path] ?: return null
        return synchronized(zip) { runCatching { zip.getInputStream(e).use { it.readBytes() } }.getOrNull() }
    }

    /** The image bytes of one tile of [kind] ("topo" or "land"), or null if the pack has none there. */
    fun tile(kind: String, z: Int, x: Int, y: Int): ByteArray? {
        val ext = (when (kind) { "topo" -> topo; "sat" -> sat; else -> land })?.ext ?: return null
        return bytes("$kind/$z/$x/$y.$ext")
    }

    // ---- line and area layers ----

    private val wholeLayers = HashMap<String, List<Feature>>()

    /**
     * The features of one grid cell of a layer (cell x, y at the layer's grid
     * zoom); for a layer stored as one file, all of it whatever the cell.
     * Reads and parses the file: call off the main thread.
     */
    fun cell(id: String, x: Int, y: Int): List<Feature> {
        val info = layers.firstOrNull { it.id == id } ?: return emptyList()
        if (info.grid == null) return synchronized(wholeLayers) {
            wholeLayers.getOrPut(id) { parse(info.path) }
        }
        return parse("layers/$id/$x/$y.geojson")
    }

    /** Places of one kind in the grid cells around a position ([rings] cells each way). Reads files: call off the main thread. */
    fun placesNear(lat: Double, lon: Double, cat: String, rings: Int = 2): List<Feature> {
        val info = layers.firstOrNull { it.point && it.grid != null } ?: return emptyList()
        val g = info.grid!!
        val x = Geo.tileX(lon, g); val y = Geo.tileY(lat, g)
        val out = ArrayList<Feature>()
        for (cx in x - rings..x + rings) for (cy in y - rings..y + rings) {
            if ("layers/${info.id}/$cx/$cy.geojson" !in entries) continue
            parse("layers/${info.id}/$cx/$cy.geojson").filterTo(out) { it.cat == cat }
        }
        return out
    }

    private fun parse(path: String): List<Feature> {
        val text = bytes(path)?.toString(Charsets.UTF_8) ?: return emptyList()
        return runCatching { parseGeoJson(text) }.getOrDefault(emptyList())
    }

    /** Roads and trails (world-fraction lines) touching a lat/lon box — what a route may walk along. */
    private fun routableLines(w: Double, s: Double, e: Double, n: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val x0 = Geo.x(w); val x1 = Geo.x(e); val y0 = Geo.y(n); val y1 = Geo.y(s)
        for (info in layers) {
            if (info.id !in ROUTABLE) continue
            val cells = if (info.grid == null) listOf(0 to 0) else {
                val k = Geo.tilesAcross(info.grid)
                ((x0 * k).toInt()..(x1 * k).toInt()).flatMap { cx -> ((y0 * k).toInt()..(y1 * k).toInt()).map { cy -> cx to cy } }
            }
            if (cells.size > 64) continue // a window this large is not routed anyway
            for ((cx, cy) in cells) for (f in cell(info.id, cx, cy)) {
                if (f.maxX < x0 || f.minX > x1 || f.maxY < y0 || f.minY > y1) continue
                out += f.xy
            }
        }
        return out
    }

    // ---- elevation ----

    private val elevTiles = LruCache<Long, FloatArray>(40) // 256 KB each
    private val pixels = IntArray(Geo.TILE * Geo.TILE)

    private fun elevTile(tx: Int, ty: Int): FloatArray? {
        val z = elevZoom ?: return null
        val key = tx.toLong() shl 32 or (ty.toLong() and 0xffffffffL)
        elevTiles.get(key)?.let { return it }
        val data = bytes("elev/$z/$tx/$ty.$elevExt") ?: return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        if (bmp.width != Geo.TILE || bmp.height != Geo.TILE) { bmp.recycle(); return null }
        val out = FloatArray(Geo.TILE * Geo.TILE)
        synchronized(pixels) {
            bmp.getPixels(pixels, 0, Geo.TILE, 0, 0, Geo.TILE, Geo.TILE)
            for (i in out.indices) { val p = pixels[i]; out[i] = Geo.terrarium((p shr 16) and 255, (p shr 8) and 255, p and 255) }
        }
        bmp.recycle()
        elevTiles.put(key, out)
        return out
    }

    /** The elevation tile range this pack was built with. */
    private fun elevRange(): IntArray? {
        val z = elevZoom ?: return null
        return intArrayOf(Geo.tileX(west, z), Geo.tileY(north, z), Geo.tileX(east, z), Geo.tileY(south, z))
    }

    /**
     * Elevation for whole tiles tx0..tx1 × ty0..ty1 (clamped to the pack), or
     * null if the pack has none there or the block would exceed [MAX_CELLS].
     */
    fun demTiles(tx0: Int, ty0: Int, tx1: Int, ty1: Int): Dem? {
        val z = elevZoom ?: return null
        val r = elevRange() ?: return null
        val ax = max(tx0, r[0]); val ay = max(ty0, r[1]); val bx = minOf(tx1, r[2]); val by = minOf(ty1, r[3])
        if (ax > bx || ay > by) return null
        val w = (bx - ax + 1) * Geo.TILE; val h = (by - ay + 1) * Geo.TILE
        if (w.toLong() * h > MAX_CELLS) return null
        val el = FloatArray(w * h)
        var any = false
        for (ty in ay..by) for (tx in ax..bx) {
            val t = elevTile(tx, ty) ?: continue
            val ox = (tx - ax) * Geo.TILE; val oy = (ty - ay) * Geo.TILE
            for (y in 0 until Geo.TILE) System.arraycopy(t, y * Geo.TILE, el, (oy + y) * w + ox, Geo.TILE)
            any = true
        }
        return if (any) Dem(z, ax * Geo.TILE, ay * Geo.TILE, w, h, el) else null
    }

    /** Elevation covering a lat/lon box (whole tiles), or null — see [demTiles]. */
    fun dem(w: Double, s: Double, e: Double, n: Double): Dem? {
        val z = elevZoom ?: return null
        return demTiles(Geo.tileX(w, z), Geo.tileY(n, z), Geo.tileX(e, z), Geo.tileY(s, z))
    }

    /** Ground elevation in metres at a position, or null if the pack has none there. */
    suspend fun elevation(lat: Double, lon: Double): Float? = withContext(Dispatchers.Default) {
        if (!contains(lat, lon)) null else dem(lon, lat, lon, lat)?.elevation(lat, lon)
    }

    /**
     * A walking route inside this pack, or null if an end is outside it or no
     * way is found. Searches the ground around the two ends first, then a
     * wider block if that finds nothing (the way round a ridge can be far off
     * to the side).
     */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TerrainRoute.Route? =
        withContext(Dispatchers.Default) {
            if (elevZoom == null || !contains(fromLat, fromLon) || !contains(toLat, toLon)) return@withContext null
            val midLat = (fromLat + toLat) / 2
            val kmLat = 111.32; val kmLon = 111.32 * cos(Math.toRadians(midLat))
            val spanKm = max(kotlin.math.abs(toLat - fromLat) * kmLat, kotlin.math.abs(toLon - fromLon) * kmLon)
            for (padKm in listOf(max(1.5, 0.5 * spanKm), max(6.0, 1.5 * spanKm))) {
                val w = minOf(fromLon, toLon) - padKm / kmLon; val e = maxOf(fromLon, toLon) + padKm / kmLon
                val s = minOf(fromLat, toLat) - padKm / kmLat; val n = maxOf(fromLat, toLat) + padKm / kmLat
                val d = dem(w, s, e, n) ?: continue
                val mask = TerrainRoute.trailMask(d, routableLines(w, s, e, n))
                TerrainRoute.route(d, fromLat, fromLon, toLat, toLon, mask)?.let { return@withContext it }
            }
            null
        }

    // ---- driving roads ----

    private val roadGraph: RoadGraph? by lazy { roadGrid?.let { g -> RoadGraph(g) { x, y -> bytes("layers/streets/$x/$y.bin") } } }

    /** Driving routes between two positions, fastest first (empty if this pack cannot route them). Call off the main thread. */
    fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, blockedAt: List<Pair<Double, Double>>, count: Int): List<RoadGraph.Route> {
        val g = roadGraph ?: return emptyList()
        if (!contains(fromLat, fromLon) || !contains(toLat, toLon)) return emptyList()
        return synchronized(g) {
            val blocked = blockedAt.mapNotNull { (la, lo) -> g.roadNear(la, lo)?.first }.toSet()
            g.routes(fromLat, fromLon, toLat, toLon, blocked, count)
        }
    }

    fun roadNear(lat: Double, lon: Double): Pair<Long, String>? {
        val g = roadGraph ?: return null
        if (!contains(lat, lon)) return null
        return synchronized(g) { g.roadNear(lat, lon) }
    }

    fun close() = runCatching { zip.close() }

    companion object {
        /** Largest elevation block held at once: about 46 MB, a 55 km square at 14 m cells. */
        const val MAX_CELLS = 12_000_000L

        /** Opens a pack, or returns null with the reason in [problem] if the file is not a usable pack. */
        fun open(file: File, problem: (String) -> Unit = {}): MapPack? = runCatching {
            val zip = ZipFile(file)
            // Zips made on Windows may use "\" between folders.
            val entries = HashMap<String, ZipEntry>()
            val en = zip.entries()
            while (en.hasMoreElements()) { val e = en.nextElement(); if (!e.isDirectory) entries[e.name.replace('\\', '/')] = e }
            val meta = entries["pack.json"] ?: run { zip.close(); problem("no pack.json inside"); return null }
            if (meta.size > 1_000_000) { zip.close(); problem("pack.json is too large"); return null }
            val json = JSONObject(zip.getInputStream(meta).use { it.readBytes() }.toString(Charsets.UTF_8))
            if (json.optInt("format") !in 1..2) { zip.close(); problem("made for a different BASTION version"); return null }
            MapPack(file, zip, entries, json)
        }.getOrElse { problem(it.message ?: "cannot be read"); null }

        /** Layers a person can walk along. */
        val ROUTABLE = listOf("roads", "mototrails", "trails")

        /** The kinds of place a pack can hold, in the order they are offered. */
        val PLACE_KINDS = listOf(
            "Fuel", "Groceries", "Convenience store", "Water", "Pharmacy", "Hospital", "Clinic", "Ambulance",
            "Fire station", "Police", "Ranger station", "Vet", "Hardware", "Vehicle repair", "Outdoor gear", "Campground",
        )

        private val NAME_KEYS = listOf("name", "trail_name", "wildernessname", "gnis_name")
        private val DETAIL_KEYS = listOf(
            "id", "trail_no", "mvum_symbol_name", "surfacetype", "trail_type", "typical_trail_grade",
            "passengervehicle_datesopen", "highclearancevehicle_datesopen", "atv_datesopen", "motorcycle_datesopen",
            "addr", "phone", "hours",
        )

        internal fun parseGeoJson(text: String): List<Feature> {
            val out = ArrayList<Feature>()
            val feats = JSONObject(text).optJSONArray("features") ?: return out
            for (i in 0 until feats.length()) {
                val f = feats.optJSONObject(i) ?: continue
                val g = f.optJSONObject("geometry") ?: continue
                val p = f.optJSONObject("properties")
                val name = NAME_KEYS.firstNotNullOfOrNull { k -> p?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
                val detail = DETAIL_KEYS.mapNotNull { k -> p?.optString(k)?.takeIf { it.isNotBlank() && it != "null" } }
                    .distinct().joinToString(" · ").take(200)
                val c = g.optJSONArray("coordinates") ?: continue
                fun line(a: JSONArray) {
                    val xy = DoubleArray(a.length() * 2)
                    for (j in 0 until a.length()) {
                        val pt = a.getJSONArray(j)
                        xy[2 * j] = Geo.x(pt.getDouble(0)); xy[2 * j + 1] = Geo.y(pt.getDouble(1))
                    }
                    if (xy.size >= 4) out += Feature(name, detail, xy)
                }
                when (g.optString("type")) {
                    "Point" -> out += Feature(name, detail, doubleArrayOf(Geo.x(c.getDouble(0)), Geo.y(c.getDouble(1))), p?.optString("cat").orEmpty())
                    "LineString" -> line(c)
                    "MultiLineString", "Polygon" -> for (j in 0 until c.length()) line(c.getJSONArray(j))
                    "MultiPolygon" -> for (j in 0 until c.length()) { val poly = c.getJSONArray(j); for (k in 0 until poly.length()) line(poly.getJSONArray(k)) }
                }
            }
            return out
        }
    }
}

/** The map packs in BASTION's "maps" folders (phone storage and SD card), used together as one map. */
class MapPacks private constructor(private val app: Context) {

    private val _packs = MutableStateFlow<List<MapPack>>(emptyList())

    /** Coarsest first, most detailed last — the order they are drawn in. */
    val packs: StateFlow<List<MapPack>> = _packs

    private val _problems = MutableStateFlow<List<String>>(emptyList())
    val problems: StateFlow<List<String>> = _problems

    fun folders(): List<File> =
        app.externalMediaDirs.filterNotNull().map { File(it, "maps").apply { mkdirs() } }.filter { it.isDirectory }

    /** Opens every pack not yet open and drops ones whose file is gone or replaced. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        synchronized(this@MapPacks) {
            val files = folders().flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".bmap", true) }?.toList().orEmpty() }
            val stamp = { f: File -> f.absolutePath + "|" + f.length() + "|" + f.lastModified() }
            val keep = _packs.value.filter { p -> stamps[p] in files.map(stamp) }
            _packs.value.filter { it !in keep }.forEach { it.close(); stamps.remove(it) }
            val problems = ArrayList<String>()
            val added = files.filter { f -> keep.none { stamps[it] == stamp(f) } }
                .mapNotNull { f -> MapPack.open(f) { problems += "${f.name}: $it" }?.also { stamps[it] = stamp(f) } }
            _packs.value = (keep + added).sortedWith(compareBy<MapPack> { it.topo?.maxZoom ?: 0 }.thenByDescending { it.areaKm2 })
            _problems.value = problems
        }
    }

    private val stamps = HashMap<MapPack, String>()

    /** The most local pack with elevation that covers every given position. */
    private fun best(vararg at: Pair<Double, Double>): MapPack? =
        _packs.value.filter { p -> p.elevZoom != null && at.all { p.contains(it.first, it.second) } }
            .sortedWith(compareByDescending<MapPack> { it.elevZoom }.thenBy { it.areaKm2 }).firstOrNull()

    fun covers(lat: Double, lon: Double): Boolean = _packs.value.any { it.contains(lat, lon) }

    suspend fun elevation(lat: Double, lon: Double): Float? = best(lat to lon)?.elevation(lat, lon)

    class Place(val name: String, val cat: String, val detail: String, val lat: Double, val lon: Double, val meters: Double)

    /** The closest places of one kind, nearest first, from every map on the phone (about 100 km around). */
    suspend fun nearest(lat: Double, lon: Double, cat: String, max: Int = 6): List<Place> = withContext(Dispatchers.Default) {
        if (_packs.value.isEmpty()) rescan()
        _packs.value.flatMap { it.placesNear(lat, lon, cat) }
            .map { f ->
                val la = Geo.lat(f.xy[1]); val lo = Geo.lon(f.xy[0])
                Place(f.name.ifBlank { cat }, f.cat, f.detail, la, lo, Geo.meters(lat, lon, la, lo))
            }
            .distinctBy { "${it.name}|${(it.lat * 5000).toLong()}|${(it.lon * 5000).toLong()}" }
            .sortedBy { it.meters }.take(max)
    }

    /** A walking route using the most detailed pack on the phone that covers both ends, or null. */
    suspend fun routeBetween(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TerrainRoute.Route? {
        if (_packs.value.isEmpty()) rescan()
        return best(fromLat to fromLon, toLat to toLon)?.route(fromLat, fromLon, toLat, toLon)
    }

    /** The most local pack with driving roads covering both ends. */
    private fun roadPack(vararg at: Pair<Double, Double>): MapPack? =
        _packs.value.filter { p -> p.roadGrid != null && at.all { p.contains(it.first, it.second) } }.minByOrNull { it.areaKm2 }

    /** Driving routes, fastest first; the road nearest each of [blockedAt] is avoided. Empty if no pack on the phone has roads for both ends. */
    suspend fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, blockedAt: List<Pair<Double, Double>> = emptyList(), count: Int = 3): List<RoadGraph.Route> =
        withContext(Dispatchers.Default) {
            if (_packs.value.isEmpty()) rescan()
            roadPack(fromLat to fromLon, toLat to toLon)?.drive(fromLat, fromLon, toLat, toLon, blockedAt, count).orEmpty()
        }

    /** True if some pack on the phone has driving roads at this position. */
    fun hasRoads(lat: Double, lon: Double): Boolean = roadPack(lat to lon) != null

    /** The road nearest a position (to block it), as id and name. */
    suspend fun roadNear(lat: Double, lon: Double): Pair<Long, String>? = withContext(Dispatchers.Default) { roadPack(lat to lon)?.roadNear(lat, lon) }

    companion object {
        @Volatile private var inst: MapPacks? = null
        fun get(context: Context): MapPacks =
            inst ?: synchronized(this) { inst ?: MapPacks(context.applicationContext).also { inst = it } }
    }
}
