package com.bastion.terminal.map

import com.bastion.terminal.link.Cot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Long drives on the real state road packs (tools/maps/state-XX-roads.bmap, built by
 * map-add-roads.ps1): route found, plausible time, how long the search takes and how much road
 * data it reads. Measured on the PC JVM, not a phone. Skipped unless BASTION_MAPS points at the
 * folder with the packs (they are never committed):
 *   BASTION_MAPS=..\tools\maps gradlew :app:testDebugUnitTest --tests '*StatePackRouteTest*'
 */
class StatePackRouteTest {

    private data class Trip(
        val pack: String, val from: String, val fLat: Double, val fLon: Double,
        val to: String, val tLat: Double, val tLon: Double, val minHours: Double, val maxHours: Double,
        val baselineMetres: Double,
    )

    // Hours: a range around the usual drive time (speed limits, no traffic, so a little under it).
    // Metres: count=3 on the 2026-11-23 state packs before the cross-pack/snap changes.
    private val trips = listOf(
        Trip("id", "Boise", 43.6150, -116.2023, "Idaho Falls", 43.4917, -112.0339, 3.0, 4.5, 452_133.338),
        Trip("id", "Boise", 43.6150, -116.2023, "Coeur d'Alene", 47.6777, -116.7805, 5.5, 8.0, 609_341.989),
        Trip("mt", "Missoula", 46.8721, -113.9940, "Billings", 45.7833, -108.5007, 4.0, 5.8, 551_620.208),
        Trip("nv", "Reno", 39.5296, -119.8138, "Las Vegas", 36.1699, -115.1398, 6.0, 8.0, 711_410.217),
        Trip("or", "Portland", 45.5152, -122.6784, "Bend", 44.0582, -121.3153, 2.5, 4.0, 261_258.859),
        Trip("ut", "Salt Lake City", 40.7608, -111.8910, "St George", 37.0965, -113.5684, 3.5, 5.0, 486_830.690),
        Trip("wa", "Seattle", 47.6062, -122.3321, "Spokane", 47.6588, -117.4260, 3.5, 5.0, 449_169.651),
        Trip("wy", "Cheyenne", 41.1400, -104.8202, "Jackson", 43.4799, -110.7624, 5.5, 8.0, 695_341.821),
    )

    private data class City(val name: String, val lat: Double, val lon: Double)
    private data class CrossTrip(val states: List<String>, val from: City, val to: City, val minHours: Double, val maxHours: Double)

    private val boise = City("Boise", 43.6150, -116.2023)
    private val spokane = City("Spokane", 47.6588, -117.4260)
    private val saltLakeCity = City("Salt Lake City", 40.7608, -111.8910)

    // Sum of great-circle distances through towns approximates the length of each corridor.
    // The hour bounds allow for road curvature, urban access and slower sections on US routes.
    private val crossTrips = listOf(
        CrossTrip(listOf("id", "wa"), boise, spokane, 4.5, 8.5), // US-95 and US-195, about 304 mi via Lewiston
        CrossTrip(listOf("ut", "id"), saltLakeCity, boise, 4.0, 6.5), // I-15 and I-84, about 318 mi via Burley
        CrossTrip(listOf("or", "id"), City("Portland", 45.5152, -122.6784), boise, 5.0, 8.5), // I-84, about 391 mi via La Grande
        CrossTrip(listOf("nv", "or", "id"), City("Reno", 39.5296, -119.8138), boise, 5.0, 9.0), // I-80 and US-95, about 373 mi via Jordan Valley
        CrossTrip(listOf("mt", "id", "wa"), City("Missoula", 46.8721, -113.9940), spokane, 2.3, 4.5), // I-90, about 173 mi via Lookout Pass
        CrossTrip(listOf("wy", "ut"), City("Cheyenne", 41.1400, -104.8202), saltLakeCity, 5.0, 8.5), // I-80, about 391 mi via Rock Springs
    )

    @Test
    fun longDrives() {
        val dir = System.getenv("BASTION_MAPS")?.let(::File)
        assumeTrue("BASTION_MAPS not set", dir != null && dir.isDirectory)
        val bad = mutableListOf<String>()
        val only = System.getenv("BASTION_PACKS")?.split(",")
        for (trip in trips) {
            if (only != null && trip.pack !in only) continue
            val file = File(dir, "state-${trip.pack}-roads.bmap")
            if (!file.exists()) { println("SKIP ${trip.pack}: no pack"); continue }
            ZipFile(file).use { zip ->
                var cells = 0; var bytes = 0L
                val graph = RoadGraph(9) { x, y ->
                    zip.getEntry("layers/streets/$x/$y.bin")?.let { e ->
                        zip.getInputStream(e).use { it.readBytes() }.also { cells++; bytes += it.size }
                    }
                }
                val measured = measure { graph.routes(trip.fLat, trip.fLon, trip.tLat, trip.tLon, count = 3) }
                val routes = measured.result
                val label = "${trip.from} -> ${trip.to}"
                checkIdentity(label, routes) { listOf(roadSnapshot(zip)) }
                val best = routes.firstOrNull()
                val hours = best?.seconds?.div(3600)
                println(String.format(
                    Locale.ROOT,
                    "ROUTE %s %s -> %s: %s, metres=%.3f, routeSeconds=%.3f, %d route(s), computeSeconds=%.3f, " +
                        "%d cells (%.1f MiB read), peakHeapMiB=%.1f, incrementalPeakMiB=%.1f, %d roads, alts %s",
                    trip.pack, trip.from, trip.to, best?.summary() ?: "NO ROUTE",
                    best?.meters ?: Double.NaN, best?.seconds ?: Double.NaN, routes.size, measured.elapsedSeconds, cells,
                    bytes / MIB, measured.peakMiB, measured.incrementMiB, graph.loadedEdges,
                    routes.drop(1).joinToString { it.summary() },
                ))
                if (hours == null || hours < trip.minHours || hours > trip.maxHours)
                    bad += "${trip.from} -> ${trip.to}: ${best?.summary() ?: "no route"}"
                if (best != null && abs(best.meters - trip.baselineMetres) > trip.baselineMetres * 0.001)
                    bad += "${trip.from} -> ${trip.to}: ${best.meters} m differs from baseline ${trip.baselineMetres} m by more than 0.1%"
                if (routes.size != 3)
                    bad += "${trip.from} -> ${trip.to}: expected 3 routes, got ${routes.size}"
            }
        }
        if (bad.isNotEmpty()) throw AssertionError(bad.joinToString("\n"))
    }

    @Test
    fun crossStateDrives() {
        val dir = System.getenv("BASTION_MAPS")?.let(::File)
        assumeTrue("BASTION_MAPS not set", dir != null && dir.isDirectory)
        val only = System.getenv("BASTION_PACKS")?.split(",")
        val bad = mutableListOf<String>()
        var exercised = 0
        for (trip in crossTrips) {
            if (only != null && !only.containsAll(trip.states)) continue
            val files = trip.states.map { File(dir, "state-$it-roads.bmap") }
            val missing = files.filterNot { it.isFile }
            val label = "${trip.from.name} -> ${trip.to.name}"
            if (missing.isNotEmpty()) {
                println("SKIP $label: missing ${missing.joinToString { it.name }}")
                continue
            }
            exercised++
            val archives = mutableListOf<ZipFile>()
            try {
                val reads = files.map { ReadStats() }
                val sources = files.zip(reads).map { (file, stats) ->
                    val archive = ZipFile(file).also(archives::add)
                    roadSource(archive, stats)
                }
                val measured = measure {
                    CrossPackDrive.routes(sources, trip.from.lat, trip.from.lon, trip.to.lat, trip.to.lon, count = 3)
                }
                val best = measured.result.firstOrNull()
                checkIdentity(label, measured.result) { archives.map(::roadSnapshot) }
                val hours = best?.seconds?.div(3600.0)
                println(String.format(
                    Locale.ROOT,
                    "CROSS %s: miles=%.2f, hours=%.3f, metres=%.3f, routeSeconds=%.3f, %d route(s), " +
                        "computeSeconds=%.3f, peakHeapMiB=%.1f, incrementalPeakMiB=%.1f, packsUsed=%s, reads=%s",
                    label, best?.meters?.div(METRES_PER_MILE) ?: Double.NaN, hours ?: Double.NaN,
                    best?.meters ?: Double.NaN, best?.seconds ?: Double.NaN, measured.result.size,
                    measured.elapsedSeconds, measured.peakMiB, measured.incrementMiB, best?.packsUsed.orEmpty(),
                    sources.zip(reads).joinToString { (s, r) ->
                        String.format(Locale.ROOT, "%s:%d cells/%.1f MiB", s.name, r.cells, r.bytes / MIB)
                    },
                ))
                if (best == null || hours == null) {
                    bad += "$label: no route"
                    continue
                }
                if (hours !in trip.minHours..trip.maxHours)
                    bad += "$label: $hours h outside ${trip.minHours}–${trip.maxHours} h"
                if (best.packsUsed.distinct().size < 2 || !sources.map { it.name }.containsAll(best.packsUsed))
                    bad += "$label: expected at least two of the input packs, used ${best.packsUsed}"
                if (sources.any { !it.complete && it.name in best.packsUsed } && !best.reducedCoverage)
                    bad += "$label: used an incomplete pack without reducedCoverage"
                val direct = Cot.distanceMeters(trip.from.lat, trip.from.lon, trip.to.lat, trip.to.lon)
                if (best.points.size < 2 || best.offRoadStart !in 0.0..2_000.0 || best.offRoadEnd !in 0.0..2_000.0 ||
                    best.meters + best.offRoadStart + best.offRoadEnd < direct * 0.995
                ) bad += "$label: invalid route geometry or endpoint snaps"
            } finally {
                archives.forEach(ZipFile::close)
            }
        }
        assumeTrue("No selected cross-state trip has all its road packs", exercised > 0)
        if (bad.isNotEmpty()) throw AssertionError(bad.joinToString("\n"))
    }

    private class ReadStats(var cells: Int = 0, var bytes: Long = 0L)

    /** JVM-only adapter for the same manifest and cell data MapPack passes to the cross-pack router. */
    private fun roadSource(archive: ZipFile, reads: ReadStats): RoadPackSource {
        val meta = JSONObject(archive.getInputStream(archive.getEntry("pack.json")).bufferedReader().use { it.readText() })
        val bbox = meta.getJSONArray("bbox")
        val layers = meta.getJSONArray("layers")
        val roads = (0 until layers.length()).map(layers::getJSONObject).single { it.optString("kind") == "roadgraph" }
        val byCell = archive.entries().asSequence().mapNotNull { entry ->
            val match = ROAD_CELL.matchEntire(entry.name.replace('\\', '/')) ?: return@mapNotNull null
            val (x, y) = match.destructured
            RoadPackSource.cellKey(x.toInt(), y.toInt()) to entry
        }.toMap()
        return RoadPackSource(
            meta.getString("name"), roads.getInt("grid"), roads.optBoolean("complete", true),
            bbox.getDouble(0), bbox.getDouble(1), bbox.getDouble(2), bbox.getDouble(3), byCell.keys,
        ) { x, y ->
            byCell[RoadPackSource.cellKey(x, y)]?.let { entry ->
                archive.getInputStream(entry).use { it.readBytes() }.also { reads.cells++; reads.bytes += it.size }
            }
        }
    }

    private data class Measurement<T>(val result: T, val elapsedSeconds: Double, val peakMiB: Double, val incrementMiB: Double)

    private fun checkIdentity(label: String, routes: List<RoadGraph.Route>, packSnapshots: () -> List<String>) {
        val actual = routeIdentities(routes)
        println("IDENTITY $label: $actual")
        val baseline = baselines[label] ?: return
        if (packSnapshots() == baseline.packs) {
            assertEquals("$label ordered route identity", baseline.routes, actual)
            println("IDENTITY MATCH $label: ${actual.size} routes")
        } else {
            println("IDENTITY BASELINE OMITTED $label: road pack snapshots differ")
        }
    }

    /** Identify the baseline road cells and pack metadata without reading full map packs. */
    private fun roadSnapshot(zip: ZipFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.entries().asSequence().filter { it.name == "pack.json" || ROAD_CELL.matches(it.name) }
            .sortedBy { it.name }.forEach { entry ->
                digest.update("${entry.name}\t${entry.size}\t${entry.crc}\n".toByteArray(Charsets.UTF_8))
            }
        return digest.finish()
    }

    private fun MessageDigest.finish(): String = digest().joinToString("") { "%02x".format(it) }

    private fun routeIdentities(routes: List<RoadGraph.Route>): List<String> = routes.map { route ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
        fun write(value: Long) {
            buffer.putLong(0, value)
            digest.update(buffer.array())
        }
        write(route.edgeIds.size.toLong())
        route.edgeIds.forEach(::write)
        write(route.points.size.toLong())
        for ((lat, lon) in route.points) {
            write(lat.toRawBits())
            write(lon.toRawBits())
        }
        write(route.meters.toRawBits())
        write(route.seconds.toRawBits())
        digest.finish()
    }

    private fun <T> measure(block: () -> T): Measurement<T> {
        val runtime = Runtime.getRuntime()
        fun usedBytes() = runtime.totalMemory() - runtime.freeMemory()
        System.gc()
        Thread.sleep(75)
        val before = usedBytes()
        val peak = AtomicLong(before)
        val running = AtomicBoolean(true)
        // Sample JVM used heap throughout the search. This can miss spikes between samples.
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(usedBytes(), ::maxOf)
                Thread.sleep(10)
            }
        }.apply { isDaemon = true; start() }
        try {
            val t0 = System.nanoTime()
            val result = block()
            val seconds = (System.nanoTime() - t0) / 1_000_000_000.0
            val top = peak.accumulateAndGet(usedBytes(), ::maxOf)
            return Measurement(result, seconds, top / MIB, (top - before) / MIB)
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private companion object {
        const val MIB = 1_048_576.0
        const val METRES_PER_MILE = 1_609.344
        val ROAD_CELL = Regex("layers/streets/(\\d+)/(\\d+)\\.bin")

        private data class Baseline(val packs: List<String>, val routes: List<String>)

        private const val IDAHO_SNAPSHOT = "a55da24ed604bfb07a35bf78f44f9d6f91d351be2a9d860d8d0055ee0790d5b6"
        private const val WASHINGTON_SNAPSHOT = "f7f2155879cf56c2d4f541c86c4488d18fdb4478a1db09dae94955df6299612a"
        private val baselines = mapOf(
            "Boise -> Idaho Falls" to Baseline(listOf(IDAHO_SNAPSHOT), listOf(
                "0c3d1ccf27b5fe0eddd5135e6b99de263f60f637b3734d2b50b5fc5391acf0d8",
                "5eda5edd3f6bcafb847edc4be0a8fab6940453a47f9b67f96c1930267615d91e",
                "658ecce17f1bfae1b975461b1ceae3f4e07e71b230ca0402713becc41ea4d1ae",
            )),
            "Boise -> Spokane" to Baseline(listOf(IDAHO_SNAPSHOT, WASHINGTON_SNAPSHOT), listOf(
                "fb32eb6cf90edd5135063717a071926868915bb22604924994779b90881ed407",
                "a040ce533ae02667713ee9938de867735d106f9f29afd4162849c5a2b2e408c0",
                "1be98deadeb17bcda1a617a314d36a4fde69178bb19ed9c665c0c0be82994de6",
            )),
        )
    }
}
