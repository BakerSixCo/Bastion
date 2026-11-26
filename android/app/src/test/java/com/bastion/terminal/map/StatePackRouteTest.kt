package com.bastion.terminal.map

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

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
    )

    // Hours: a range around the usual drive time (speed limits, no traffic, so a little under it).
    private val trips = listOf(
        Trip("id", "Boise", 43.6150, -116.2023, "Idaho Falls", 43.4917, -112.0339, 3.0, 4.5),
        Trip("id", "Boise", 43.6150, -116.2023, "Coeur d'Alene", 47.6777, -116.7805, 5.5, 8.0),
        Trip("mt", "Missoula", 46.8721, -113.9940, "Billings", 45.7833, -108.5007, 4.0, 5.8),
        Trip("nv", "Reno", 39.5296, -119.8138, "Las Vegas", 36.1699, -115.1398, 6.0, 8.0),
        Trip("or", "Portland", 45.5152, -122.6784, "Bend", 44.0582, -121.3153, 2.5, 4.0),
        Trip("ut", "Salt Lake City", 40.7608, -111.8910, "St George", 37.0965, -113.5684, 3.5, 5.0),
        Trip("wa", "Seattle", 47.6062, -122.3321, "Spokane", 47.6588, -117.4260, 3.5, 5.0),
        Trip("wy", "Cheyenne", 41.1400, -104.8202, "Jackson", 43.4799, -110.7624, 5.5, 8.0),
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
                val rt = Runtime.getRuntime()
                System.gc(); val before = rt.totalMemory() - rt.freeMemory()
                // Peak heap, sampled while the search runs (java.lang.management is not on Android's classpath).
                val top = java.util.concurrent.atomic.AtomicLong(before)
                val running = java.util.concurrent.atomic.AtomicBoolean(true)
                val sampler = Thread { while (running.get()) { top.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf); Thread.sleep(10) } }
                sampler.start()
                val t0 = System.nanoTime()
                val routes = graph.routes(trip.fLat, trip.fLon, trip.tLat, trip.tLon, count = 3)
                val ms = (System.nanoTime() - t0) / 1_000_000
                running.set(false); sampler.join()
                val peak = (top.get() - before) / 1_048_576
                System.gc()
                val used = (rt.totalMemory() - rt.freeMemory() - before) / 1_048_576
                val best = routes.firstOrNull()
                val hours = best?.seconds?.div(3600)
                println(String.format(
                    "ROUTE %s %s -> %s: %s, %d route(s), %d ms, %d cells (%.0f MB read), peak +%d MB, network kept %d MB (%d roads), alts %s",
                    trip.pack, trip.from, trip.to, best?.summary() ?: "NO ROUTE", routes.size, ms, cells,
                    bytes / 1_048_576.0, peak, used, graph.loadedEdges, routes.drop(1).joinToString { it.summary() },
                ))
                if (hours == null || hours < trip.minHours || hours > trip.maxHours)
                    bad += "${trip.from} -> ${trip.to}: ${best?.summary() ?: "no route"}"
            }
        }
        if (bad.isNotEmpty()) throw AssertionError("implausible: " + bad.joinToString("; "))
    }
}
