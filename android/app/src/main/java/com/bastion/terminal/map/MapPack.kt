package com.bastion.terminal.map

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * One offline map pack (`.bmap`, built on a PC by `tools/map-build.ps1`): a
 * zip holding map tiles, elevation tiles and line/area layers for one area.
 * Read-only; BASTION never downloads map data itself.
 *
 * Everything in a pack is **data to draw**, never instructions.
 */
class MapPack private constructor(val file: File, private val zip: ZipFile, private val entries: Map<String, ZipEntry>, json: JSONObject) {

    data class Raster(val minZoom: Int, val maxZoom: Int, val ext: String)
    data class LayerInfo(val id: String, val title: String, val area: Boolean, val path: String, val count: Int)

    /** A line or outline: world-fraction x,y pairs, with its bounds for quick skipping. */
    class Feature(val name: String, val detail: String, val xy: DoubleArray) {
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
    val elevZoom: Int? = json.optJSONObject("elev")?.optInt("zoom")
    private val elevExt = json.optJSONObject("elev")?.optString("ext", "png") ?: "png"
    val layers: List<LayerInfo>

    init {
        val b = json.getJSONArray("bbox")
        west = b.getDouble(0); south = b.getDouble(1); east = b.getDouble(2); north = b.getDouble(3)
        val arr = json.optJSONArray("layers") ?: JSONArray()
        layers = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            LayerInfo(it.getString("id"), it.optString("title", it.getString("id")), it.optString("kind") == "area", it.getString("file"), it.optInt("count"))
        }.filter { it.path in entries && it.count > 0 }
    }

    private fun raster(o: JSONObject?): Raster? =
        o?.let { Raster(it.getInt("minZoom"), it.getInt("maxZoom"), it.optString("ext", "png")) }

    private fun bytes(path: String): ByteArray? {
        val e = entries[path] ?: return null
        return synchronized(zip) { runCatching { zip.getInputStream(e).use { it.readBytes() } }.getOrNull() }
    }

    /** The image bytes of one tile of [kind] ("topo" or "land"), or null if the pack has none there. */
    fun tile(kind: String, z: Int, x: Int, y: Int): ByteArray? {
        val ext = (if (kind == "topo") topo else land)?.ext ?: return null
        return bytes("$kind/$z/$x/$y.$ext")
    }

    private val featureCache = HashMap<String, List<Feature>>()

    /** A layer's lines or outlines (parsed once, then kept). */
    fun features(id: String): List<Feature> = synchronized(featureCache) {
        featureCache.getOrPut(id) {
            val info = layers.firstOrNull { it.id == id } ?: return@getOrPut emptyList()
            val text = bytes(info.path)?.toString(Charsets.UTF_8) ?: return@getOrPut emptyList()
            runCatching { parseGeoJson(text) }.getOrDefault(emptyList())
        }
    }

    @Volatile private var demCache: Dem? = null

    /** The pack's elevation as one block (decoded once, about 11 MB for a 20 km pack), or null if it has none. */
    suspend fun dem(): Dem? = withContext(Dispatchers.Default) {
        demCache?.let { return@withContext it }
        val z = elevZoom ?: return@withContext null
        synchronized(this@MapPack) {
            demCache?.let { return@synchronized it }
            val tx0 = Geo.tileX(west, z); val tx1 = Geo.tileX(east, z)
            val ty0 = Geo.tileY(north, z); val ty1 = Geo.tileY(south, z)
            val w = (tx1 - tx0 + 1) * Geo.TILE; val h = (ty1 - ty0 + 1) * Geo.TILE
            if (w.toLong() * h > 16_000_000L) return@synchronized null // a pack far larger than intended
            val el = FloatArray(w * h)
            val row = IntArray(Geo.TILE * Geo.TILE)
            val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; inScaled = false }
            var any = false
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val data = bytes("elev/$z/$tx/$ty.$elevExt") ?: continue
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: continue
                if (bmp.width != Geo.TILE || bmp.height != Geo.TILE) { bmp.recycle(); continue }
                bmp.getPixels(row, 0, Geo.TILE, 0, 0, Geo.TILE, Geo.TILE)
                bmp.recycle()
                val ox = (tx - tx0) * Geo.TILE; val oy = (ty - ty0) * Geo.TILE
                for (y in 0 until Geo.TILE) for (x in 0 until Geo.TILE) {
                    val p = row[y * Geo.TILE + x]
                    el[(oy + y) * w + ox + x] = Geo.terrarium((p shr 16) and 255, (p shr 8) and 255, p and 255)
                }
                any = true
            }
            if (!any) null else Dem(z, tx0 * Geo.TILE, ty0 * Geo.TILE, w, h, el).also { demCache = it }
        }
    }

    @Volatile private var maskCache: BooleanArray? = null

    /** Cells of the elevation block that a road or trail in this pack passes through (worked out once). */
    suspend fun trailMask(): BooleanArray? = withContext(Dispatchers.Default) {
        maskCache ?: dem()?.let { d ->
            val lines = ROUTABLE.flatMap { id -> features(id).map { it.xy } }
            TerrainRoute.trailMask(d, lines).also { maskCache = it }
        }
    }

    fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east

    /** A walking route inside this pack, or null if an end is outside it or no way is found. */
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TerrainRoute.Route? =
        withContext(Dispatchers.Default) {
            if (!contains(fromLat, fromLon) || !contains(toLat, toLon)) return@withContext null
            val d = dem() ?: return@withContext null
            TerrainRoute.route(d, fromLat, fromLon, toLat, toLon, trailMask())
        }

    fun close() = runCatching { zip.close() }

    companion object {
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
            if (json.optInt("format") != 1) { zip.close(); problem("made for a different BASTION version"); return null }
            MapPack(file, zip, entries, json)
        }.getOrElse { problem(it.message ?: "cannot be read"); null }

        /** Layers a person can walk along. */
        val ROUTABLE = listOf("roads", "mototrails", "trails")

        private val NAME_KEYS =listOf("name", "trail_name", "wildernessname", "gnis_name")
        private val DETAIL_KEYS = listOf(
            "id", "trail_no", "mvum_symbol_name", "surfacetype", "trail_type", "typical_trail_grade",
            "passengervehicle_datesopen", "highclearancevehicle_datesopen", "atv_datesopen", "motorcycle_datesopen",
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
                    "LineString" -> line(c)
                    "MultiLineString", "Polygon" -> for (j in 0 until c.length()) line(c.getJSONArray(j))
                    "MultiPolygon" -> for (j in 0 until c.length()) { val poly = c.getJSONArray(j); for (k in 0 until poly.length()) line(poly.getJSONArray(k)) }
                }
            }
            return out
        }
    }
}

/** The map packs in BASTION's "maps" folders (phone storage and SD card). */
class MapPacks private constructor(private val app: Context) {

    private val _packs = MutableStateFlow<List<MapPack>>(emptyList())
    val packs: StateFlow<List<MapPack>> = _packs

    private val _problems = MutableStateFlow<List<String>>(emptyList())
    val problems: StateFlow<List<String>> = _problems

    fun folders(): List<File> =
        app.externalMediaDirs.filterNotNull().map { File(it, "maps").apply { mkdirs() } }.filter { it.isDirectory }

    /** Opens every pack not yet open and drops ones whose file is gone. */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        synchronized(this@MapPacks) {
            val files = folders().flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".bmap", true) }?.toList().orEmpty() }
            val keep = _packs.value.filter { p -> files.any { it.absolutePath == p.file.absolutePath && it.lastModified() == p.file.lastModified() } }
            _packs.value.filter { it !in keep }.forEach { it.close() }
            val problems = ArrayList<String>()
            val added = files.filter { f -> keep.none { it.file.absolutePath == f.absolutePath } }
                .mapNotNull { f -> MapPack.open(f) { problems += "${f.name}: $it" } }
            _packs.value = (keep + added).sortedBy { it.name }
            _problems.value = problems
        }
    }

    /** A walking route using whichever pack on the phone covers both ends, or null. */
    suspend fun routeBetween(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): TerrainRoute.Route? {
        if (_packs.value.isEmpty()) rescan()
        val pack = _packs.value.firstOrNull { it.contains(fromLat, fromLon) && it.contains(toLat, toLon) } ?: return null
        return pack.route(fromLat, fromLon, toLat, toLon)
    }

    companion object {
        @Volatile private var inst: MapPacks? = null
        fun get(context: Context): MapPacks =
            inst ?: synchronized(this) { inst ?: MapPacks(context.applicationContext).also { inst = it } }
    }
}
