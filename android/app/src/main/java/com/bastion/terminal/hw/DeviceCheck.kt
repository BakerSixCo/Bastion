package com.bastion.terminal.hw

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.bastion.terminal.ai.LocalAi
import com.bastion.terminal.device.DeviceManager
import com.bastion.terminal.mesh.MeshBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Public device capabilities and capacities, without storage identifiers or asset filenames. */
internal data class DeviceFacts(
    val maker: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val abis: List<String>,
    val totalRamBytes: Long,
    val advertisedRamBytes: Long?,
    val phoneFreeBytes: Long?,
    val sdFreeBytes: List<Long?>,
    val knownNoSdSlot: Boolean,
    val gps: Boolean,
    val ble: Boolean,
    val openCl: Boolean,
    val deviceOwner: Boolean,
    val meshtasticInstalled: Boolean,
    val kiwixInstalled: Boolean,
    val atakInstalled: Boolean,
    val emulator: Boolean,
    val importedModelBytes: Long?,
) {
    val deviceLabel: String
        get() = listOf(maker, model).joinToString(" ").trim() + if (emulator) " · emulator — not a phone" else ""

    fun describeLines(): List<String> = buildList {
        add(deviceLabel)
        add("Android $androidVersion (SDK $sdk) · ABIs ${abis.joinToString(", ").ifEmpty { "unknown" }}")
        val ram = if (totalRamBytes > 0) gb(totalRamBytes) else "unknown"
        val advertised = advertisedRamBytes?.takeIf { it != totalRamBytes }?.let { " (${gb(it)} advertised)" }.orEmpty()
        add("RAM reported by Android · $ram$advertised")
        add("Free phone storage · ${phoneFreeBytes?.let(::gb) ?: "unknown"}")
        if (knownNoSdSlot) add("SD card · no SD slot")
        if (sdFreeBytes.isEmpty() && !knownNoSdSlot) add("SD card · no removable card detected (slot status unknown)")
        sdFreeBytes.forEachIndexed { i, bytes -> add("Removable media ${i + 1} · ${bytes?.let(::gb) ?: "unknown"} free") }
        add("GPS provider ${if (gps) "present" else "not present"} · Bluetooth LE ${if (ble) "present" else "not present"}")
        add("OpenCL library · ${if (openCl) "loadable (GPU backend possible)" else "not loadable (CPU backend)"}")
        add("Device owner · ${if (deviceOwner) "BASTION" else "BASTION is an ordinary app"}")
        add("Meshtastic ${installed(meshtasticInstalled)} · Kiwix ${installed(kiwixInstalled)} · ATAK-CIV ${installed(atakInstalled)}")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("maker", maker).put("model", model).put("deviceLabel", deviceLabel)
        .put("androidVersion", androidVersion).put("sdk", sdk).put("abis", JSONArray(abis))
        .put("totalRamBytes", totalRamBytes).put("advertisedRamBytes", advertisedRamBytes ?: JSONObject.NULL)
        .put("phoneFreeBytes", phoneFreeBytes ?: JSONObject.NULL)
        .put("sdFreeBytes", JSONArray(sdFreeBytes.map { it ?: JSONObject.NULL })).put("knownNoSdSlot", knownNoSdSlot)
        .put("gps", gps).put("ble", ble).put("openCl", openCl).put("deviceOwner", deviceOwner)
        .put("meshtasticInstalled", meshtasticInstalled).put("kiwixInstalled", kiwixInstalled).put("atakInstalled", atakInstalled)
        .put("emulator", emulator).put("importedModelBytes", importedModelBytes ?: JSONObject.NULL)
}

internal data class DeviceRequirement(val name: String, val met: Boolean, val reason: String)

/** Expected and Not supported are the only tiers an automated phone check can set. */
internal data class DeviceVerdict(
    val requirements: List<DeviceRequirement>,
    val notes: List<String>,
    val emulator: Boolean,
) {
    val meetsRequirements: Boolean get() = requirements.all { it.met }
    val tier: String get() = if (meetsRequirements) "Expected" else "Not supported"
    val summary: String
        get() = if (meetsRequirements) "Meets requirements (Expected)"
        else "Not supported: " + requirements.filterNot { it.met }.joinToString(" ") { it.reason }

    fun describeLines(): List<String> =
        listOf(summary) + requirements.map { "${it.name} · ${if (it.met) "meets" else "fails"} · ${it.reason}" } + notes

    fun toJson(): JSONObject = JSONObject()
        .put("meetsRequirements", meetsRequirements).put("tier", tier).put("summary", summary).put("emulator", emulator)
        .put("requirements", JSONArray(requirements.map {
            JSONObject().put("name", it.name).put("met", it.met).put("reason", it.reason)
        }))
        .put("notes", JSONArray(notes))
}

internal object DeviceCheck {
    // Compatibility floors and the reference E2B model come from docs/build-sheet.md, open decision 9.
    internal const val MIN_SDK = 29
    internal const val REQUIRED_ABI = "arm64-v8a"
    internal const val MIN_RAM_BYTES = 8_000_000_000L
    internal const val E4B_RAM_BYTES = 12_000_000_000L
    internal const val REFERENCE_MODEL_BYTES = 2_588_147_712L

    /** No permissions are requested. Filesystem and native library queries belong off the main thread. */
    fun readFacts(context: Context): DeviceFacts {
        val app = context.applicationContext
        val packages = app.packageManager
        val memory = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val advertised = if (Build.VERSION.SDK_INT >= 34) memory.advertisedMem.takeIf { it > 0 } else null
        val removable = app.externalMediaDirs.filterNotNull().filter {
            runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
        }
        val gps = app.getSystemService(LocationManager::class.java)?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true
        return DeviceFacts(
            maker = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = memory.totalMem,
            advertisedRamBytes = advertised,
            phoneFreeBytes = freeBytes(app.cacheDir),
            sdFreeBytes = removable.map(::freeBytes),
            knownNoSdSlot = knownNoSdSlot(Build.MANUFACTURER, Build.MODEL),
            gps = gps,
            ble = packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            openCl = loadOpenCl(),
            deviceOwner = DeviceManager.isOwner(app),
            meshtasticInstalled = MeshBridge.isInstalled(app),
            kiwixInstalled = packageInstalled(packages, "org.kiwix.kiwixmobile") ||
                packageInstalled(packages, "org.kiwix.kiwixmobile.standalone"),
            atakInstalled = packageInstalled(packages, "com.atakmap.app.civ"),
            emulator = isEmulator(Build.HARDWARE, Build.PRODUCT, Build.MODEL),
            importedModelBytes = LocalAi.get(app).currentModelFile()?.length(),
        )
    }

    /** A pure check. An imported model occupies storage already, so only the start reserve remains. */
    fun evaluate(facts: DeviceFacts): DeviceVerdict {
        val android = facts.sdk >= MIN_SDK
        val arm64 = REQUIRED_ABI in facts.abis
        val advertised = facts.advertisedRamBytes?.takeIf { it > 0 }
        val ramBytes = advertised ?: facts.totalRamBytes
        val ram = ramBytes >= MIN_RAM_BYTES
        val model = facts.importedModelBytes ?: REFERENCE_MODEL_BYTES
        val extra = model / 3 // Same first-load cache allowance as LocalAi.obtain().
        val requiredFree = extra + if (facts.importedModelBytes == null) model else 0L
        val free = facts.phoneFreeBytes
        val storageNeed = if (facts.importedModelBytes == null) {
            "${gb(requiredFree)} free on the phone for the reference E2B model (${gb(model)}) and its first-load reserve (${gb(extra)})"
        } else {
            "${gb(extra)} free on the phone for the model's first-load reserve (${gb(model)} already imported)"
        }
        return DeviceVerdict(
            requirements = listOf(
                DeviceRequirement("Android 10+", android, if (android) "Android reports SDK ${facts.sdk}." else
                    "Android 10 or later (SDK $MIN_SDK) is required. Android reports SDK ${facts.sdk}."),
                DeviceRequirement("64-bit ARM", arm64, if (arm64) "$REQUIRED_ABI is available." else
                    "$REQUIRED_ABI is required. Android reports ${facts.abis.joinToString(", ").ifEmpty { "no supported ABIs" }}."),
                DeviceRequirement("GPS", facts.gps, if (facts.gps) "Android lists a GPS provider." else "Android lists no GPS provider."),
                DeviceRequirement("Bluetooth LE", facts.ble, if (facts.ble) "Android declares Bluetooth LE support." else
                    "Android declares no Bluetooth LE support."),
                DeviceRequirement("Phone storage", free != null && free >= requiredFree,
                    "Need $storageNeed. ${free?.let { "${gb(it)} available." } ?: "Available space could not be read."}"),
                DeviceRequirement("8 GB RAM", ram, if (ramBytes <= 0) "Could not confirm the 8 GB RAM minimum." else
                    "Need at least 8 GB RAM. Android ${if (advertised == null) "reports" else "advertises"} ${gb(ramBytes)}."),
            ),
            notes = listOf(if (ramBytes >= E4B_RAM_BYTES)
                "Meets the 12 GB memory target for Gemma 4 E4B." else "Gemma 4 E4B has a 12 GB memory target."),
            emulator = facts.emulator,
        )
    }

    // Slot availability is not reported by Android. These two model families are known from the task brief.
    internal fun knownNoSdSlot(maker: String, model: String): Boolean =
        (maker.equals("Google", ignoreCase = true) && model.equals("Pixel 9a", ignoreCase = true)) ||
            (maker.equals("Samsung", ignoreCase = true) &&
                (model.startsWith("SM-S926", ignoreCase = true) || model.equals("Galaxy S24+", ignoreCase = true)))

    internal fun isEmulator(hardware: String, product: String, model: String): Boolean =
        hardware.lowercase() in setOf("goldfish", "ranchu", "vbox86") ||
            product.startsWith("sdk_gphone", ignoreCase = true) || product.equals("sdk", ignoreCase = true) ||
            model.startsWith("Android SDK built for", ignoreCase = true)

    private fun freeBytes(dir: File): Long? = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInstalled(packages: PackageManager, name: String): Boolean = try {
        packages.getPackageInfo(name, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun loadOpenCl(): Boolean = try {
        System.loadLibrary("OpenCL")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
private fun installed(wasFound: Boolean): String = if (wasFound) "installed" else "not installed"
