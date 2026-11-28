package com.bastion.terminal.hw

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.bastion.terminal.ai.AiState
import com.bastion.terminal.ai.AskSession
import com.bastion.terminal.ai.LiteRtLmModel
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.kiwix.ZimLibrary
import com.bastion.terminal.kiwix.ZimState
import com.bastion.terminal.map.MapPack
import com.bastion.terminal.map.MapPacks
import com.bastion.terminal.map.RoadGraph
import com.bastion.terminal.mesh.MeshAutoResponder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class DeviceMeasurementResult(
    val model: Model,
    val zim: TimedCheck,
    val road: TimedCheck,
    val peakRssMiB: Long?,
    val batteryBefore: Battery,
    val batteryAfter: Battery,
) {
    data class Model(
        val loadSeconds: Double? = null,
        val alreadyLoaded: Boolean = false,
        val backend: String? = null,
        val promptTokensPerSecond: Double? = null,
        val decodeTokensPerSecond: Double? = null,
        val unavailable: String? = null,
    )

    data class TimedCheck(
        val elapsedMs: Double? = null,
        val returnedResult: Boolean? = null,
        val unavailable: String? = null,
    )

    data class Battery(val percent: Int?, val temperatureC: Double?)

    fun toJson(): JSONObject = JSONObject()
        .put("model", JSONObject()
            .put("loadSeconds", jsonValue(model.loadSeconds))
            .put("alreadyLoaded", model.alreadyLoaded)
            .put("backend", jsonValue(model.backend))
            .put("promptTokensPerSecond", jsonValue(model.promptTokensPerSecond))
            .put("decodeTokensPerSecond", jsonValue(model.decodeTokensPerSecond))
            .put("unavailable", jsonValue(model.unavailable)))
        .put("zim", zim.toJson())
        .put("road", road.toJson())
        .put("peakRssMiB", jsonValue(peakRssMiB))
        .put("batteryBefore", batteryBefore.toJson())
        .put("batteryAfter", batteryAfter.toJson())

    fun describeLines(): List<String> = buildList {
        if (model.unavailable != null) {
            add("Model · not measured: ${model.unavailable}")
        } else {
            model.backend?.let { add("Model backend · $it") }
            val load = if (model.alreadyLoaded) "not measured: model already loaded" else number(model.loadSeconds, "s")
            add("Model load · $load")
            add("Model prompt · ${number(model.promptTokensPerSecond, "tokens/s")}")
            add("Model decode · ${number(model.decodeTokensPerSecond, "tokens/s")}")
        }
        add(zim.describe("ZIM search", "hits returned", "no hits returned"))
        add(road.describe("Driving route", "route returned", "no route returned"))
        add("Peak sampled app RAM (VmRSS) · ${peakRssMiB?.let { "$it MiB" } ?: "not measured"}")
        add(batteryBefore.describe("Battery before"))
        add(batteryAfter.describe("Battery after"))
    }

    private fun TimedCheck.toJson() = JSONObject()
        .put("elapsedMs", jsonValue(elapsedMs))
        .put("returnedResult", jsonValue(returnedResult))
        .put("unavailable", jsonValue(unavailable))

    private fun Battery.toJson() = JSONObject()
        .put("percent", jsonValue(percent))
        .put("temperatureC", jsonValue(temperatureC))

    private fun TimedCheck.describe(label: String, present: String, absent: String): String {
        if (unavailable != null) return "$label · not measured: $unavailable"
        val result = if (returnedResult == true) present else absent
        return "$label · ${number(elapsedMs, "ms")}, $result"
    }

    private fun Battery.describe(label: String) =
        "$label · ${percent?.let { "$it%" } ?: "charge unavailable"}, ${number(temperatureC, "°C", "temperature unavailable")}"

    private fun number(value: Double?, unit: String, missing: String = "not measured") =
        value?.let { String.format(Locale.ROOT, "%.1f %s", it, unit) } ?: missing

    private fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL
}

/** Optional benchmarking. Only fixed inputs and numeric results cross the report boundary. */
internal object DeviceMeasurements {
    private const val RSS_SAMPLE_MS = 100L
    private val lock = Mutex()
    private data class Point(val lat: Double, val lon: Double)
    private data class Drive(val from: Point, val to: Point)

    // Fixed public endpoints also used by StatePackRouteTest. The report stores neither endpoint.
    private val stateDrives = mapOf(
        "state-id" to Drive(Point(43.6150, -116.2023), Point(43.4917, -112.0339)),
        "state-mt" to Drive(Point(46.8721, -113.9940), Point(45.7833, -108.5007)),
        "state-nv" to Drive(Point(39.5296, -119.8138), Point(36.1699, -115.1398)),
        "state-or" to Drive(Point(45.5152, -122.6784), Point(44.0582, -121.3153)),
        "state-ut" to Drive(Point(40.7608, -111.8910), Point(37.0965, -113.5684)),
        "state-wa" to Drive(Point(47.6062, -122.3321), Point(47.6588, -117.4260)),
        "state-wy" to Drive(Point(41.1400, -104.8202), Point(43.4799, -110.7624)),
    )

    suspend fun run(context: Context, onProgress: (String) -> Unit): DeviceMeasurementResult =
        lock.withLock {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val app = context.applicationContext
                    val peak = AtomicLong(-1L)
                    fun sample() {
                        LiteRtLmModel.readRssMb()?.let { value -> peak.accumulateAndGet(value, ::maxOf) }
                    }
                    val before = readBattery(app)
                    sample()
                    val sampler = launch {
                        while (isActive) {
                            delay(RSS_SAMPLE_MS)
                            sample()
                        }
                    }
                    try {
                        onProgress("Measuring the model…")
                        val model = attempt(DeviceMeasurementResult.Model(unavailable = "model check failed")) {
                            measureModel(app)
                        }
                        currentCoroutineContext().ensureActive()
                        onProgress("Searching the offline reference…")
                        val zim = attempt(DeviceMeasurementResult.TimedCheck(unavailable = "ZIM check failed")) {
                            measureZim(app)
                        }
                        currentCoroutineContext().ensureActive()
                        onProgress("Calculating a driving route…")
                        val road = attempt(DeviceMeasurementResult.TimedCheck(unavailable = "road pack check failed")) {
                            measureRoad(app)
                        }
                        currentCoroutineContext().ensureActive()
                        sample()
                        DeviceMeasurementResult(model, zim, road, peak.get().takeIf { it >= 0 }, before, readBattery(app))
                    } finally {
                        sampler.cancel()
                    }
                }
            }
        }

    private suspend fun measureModel(app: Context): DeviceMeasurementResult.Model {
        val ai = LocalAi.get(app)
        if (ai.currentModelFile() == null) return DeviceMeasurementResult.Model(unavailable = "no model imported")
        if (AskSession.get(app).generating || ai.state.value is AiState.Loading || ai.state.value is AiState.Importing) {
            return DeviceMeasurementResult.Model(unavailable = "the model is busy")
        }
        if (MeshAutoResponder.isEnabled(app)) {
            return DeviceMeasurementResult.Model(unavailable = "radio auto-answer is enabled")
        }
        val start = System.nanoTime()
        val alreadyLoaded = ai.state.value is AiState.Loaded
        val local = ai.obtain()
        currentCoroutineContext().ensureActive()
        if (local == null) return DeviceMeasurementResult.Model(unavailable = "the imported model could not start")
        val loadSeconds = if (alreadyLoaded) null else elapsedMs(start) / 1_000.0
        val stats = coroutineScope {
            val worker = async {
                local.generate(
                    "Offline hardware check. Follow the simple prompt.",
                    "Write one sentence describing a fictional blue cube.",
                    maxOutputTokens = 32,
                ).collect { /* Generated text is discarded. */ }
                local.lastStats()
            }
            try {
                worker.await()
            } catch (cancelled: CancellationException) {
                local.cancel()
                throw cancelled
            }
        }
        return DeviceMeasurementResult.Model(
            loadSeconds,
            alreadyLoaded,
            backend = (local as? LiteRtLmModel)?.activeBackend?.name,
            promptTokensPerSecond = stats?.prefillTokensPerSecond?.takeIf { it.isFinite() && it > 0 },
            decodeTokensPerSecond = stats?.decodeTokensPerSecond?.takeIf { it.isFinite() && it > 0 },
        )
    }

    private suspend fun measureZim(app: Context): DeviceMeasurementResult.TimedCheck {
        val library = ZimLibrary.get(app)
        if (library.currentInfos().isEmpty()) library.restore()
        val infos = library.currentInfos()
        if (infos.isEmpty()) {
            val reason = if (library.state.value == ZimState.NoZim) "no ZIM present" else "no readable ZIM"
            return DeviceMeasurementResult.TimedCheck(unavailable = reason)
        }
        val zim = infos.filter { it.hasFulltextIndex }.maxByOrNull { it.sizeBytes }
            ?: return DeviceMeasurementResult.TimedCheck(unavailable = "no ZIM with a full-text index")
        val start = System.nanoTime()
        val found = library.search(listOf("map"), max = 1) { it == zim.file }.isNotEmpty()
        return DeviceMeasurementResult.TimedCheck(elapsedMs(start), found)
    }

    private suspend fun measureRoad(app: Context): DeviceMeasurementResult.TimedCheck {
        val maps = MapPacks.get(app)
        if (maps.packs.value.isEmpty()) maps.rescan()
        val pack = maps.packs.value.filter { it.roadGrid != null }
            .maxWithOrNull(compareBy<MapPack> { it.file.length() }.thenBy { it.name })
            ?: return DeviceMeasurementResult.TimedCheck(unavailable = "no road pack present")
        val key = pack.file.nameWithoutExtension.lowercase(Locale.ROOT).removeSuffix("-roads")
        val known = stateDrives[key]?.takeIf { pack.contains(it.from.lat, it.from.lon) && pack.contains(it.to.lat, it.to.lon) }
        val drive = known ?: probeDrive(pack)
            ?: return DeviceMeasurementResult.TimedCheck(unavailable = "no benchmark endpoints found in the largest road pack")
        currentCoroutineContext().ensureActive()
        val start = System.nanoTime()
        val routes = pack.drive(
            drive.from.lat, drive.from.lon, drive.to.lat, drive.to.lon,
            blockedAt = emptyList(), count = 1, vehicle = RoadGraph.Vehicle.CAR,
        )
        return DeviceMeasurementResult.TimedCheck(elapsedMs(start), routes.isNotEmpty())
    }

    private suspend fun probeDrive(pack: MapPack): Drive? {
        val points = ArrayList<Point>()
        for (dy in listOf(0.25, 0.50, 0.75)) for (dx in listOf(0.25, 0.50, 0.75)) {
            currentCoroutineContext().ensureActive()
            val lat = pack.south + (pack.north - pack.south) * dy
            val lon = pack.west + (pack.east - pack.west) * dx
            val hit = pack.roadNear(lat, lon) ?: continue
            if (pack.contains(hit.lat, hit.lon)) points += Point(hit.lat, hit.lon)
        }
        val from = points.firstOrNull() ?: return null
        val to = points.drop(1).maxByOrNull { RoadGraph.meters(from.lat, from.lon, it.lat, it.lon) } ?: return null
        if (from == to) return null
        return Drive(from, to)
    }

    private fun readBattery(app: Context): DeviceMeasurementResult.Battery = try {
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val percent = if (scale > 0 && level in 0..scale) (level * 100.0 / scale).roundToInt() else null
        DeviceMeasurementResult.Battery(percent, temp.takeUnless { it == Int.MIN_VALUE }?.div(10.0))
    } catch (_: SecurityException) {
        DeviceMeasurementResult.Battery(null, null)
    }

    private fun elapsedMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0

    private suspend fun <T> attempt(failure: T, measure: suspend () -> T): T = try {
        measure()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure
    } catch (_: LinkageError) {
        failure
    }
}
