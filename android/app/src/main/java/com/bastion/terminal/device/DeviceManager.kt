package com.bastion.terminal.device

import android.Manifest
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/** Android requires a receiver for a device-owner app; BASTION needs no callbacks from it. */
class BastionAdmin : DeviceAdminReceiver()

/**
 * BASTION as the phone's **device manager** (Android "device owner"; owner
 * decision 2026-11-22). On a dedicated, factory-reset phone the PC setup tool
 * gives BASTION this role once. BASTION can then:
 *  - install and update the companion apps from the kit folder with no prompts,
 *  - grant the permissions it and they need with no tap-through screens,
 *  - be the home screen, and lock the phone to BASTION and its companions.
 *
 * Without the role every function here is a no-op that says so — BASTION
 * works as an ordinary app on an ordinary phone. Nothing here downloads
 * anything: apps are installed only from files already on the phone.
 */
object DeviceManager {
    private const val TAG = "BastionDevice"

    /** Companion apps BASTION manages, with the permissions each needs to do its job. */
    val COMPANIONS: Map<String, List<String>> = mapOf(
        "com.geeksville.mesh" to listOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.NEARBY_WIFI_DEVICES",
        ),
        "com.atakmap.app.civ" to listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, "android.permission.ACCESS_LOCAL_NETWORK",
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        ),
        "app.organicmaps" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "org.kiwix.kiwixmobile" to listOf(Manifest.permission.POST_NOTIFICATIONS),
        // The same apps as published outside the Play Store (the builds the kit uses).
        "app.organicmaps.web" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "org.kiwix.kiwixmobile.standalone" to listOf(Manifest.permission.POST_NOTIFICATIONS),
    )
    private val OWN = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS, "android.permission.ACCESS_LOCAL_NETWORK",
    )

    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    private fun admin(c: Context) = ComponentName(c.applicationContext, BastionAdmin::class.java)

    fun isOwner(c: Context): Boolean = runCatching { dpm(c).isDeviceOwnerApp(c.packageName) }.getOrDefault(false)

    private fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Where the PC setup tool puts companion APKs: `Android/media/com.bastion.terminal/kit/`. */
    fun kitFolder(c: Context): File {
        val base = c.externalMediaDirs.firstOrNull { it != null } ?: c.getExternalFilesDir(null) ?: c.filesDir
        return File(base, "kit").apply { mkdirs() }
    }

    /**
     * Grants permissions to BASTION and every installed companion, and makes
     * BASTION the home screen. Returns plain-words lines of what was done.
     */
    fun apply(c: Context): List<String> {
        if (!isOwner(c)) return listOf("BASTION is not this phone's device manager, so nothing was changed.")
        val d = dpm(c)
        val a = admin(c)
        val out = ArrayList<String>()
        fun grant(pkg: String, perms: List<String>): Int = perms.count { p ->
            runCatching {
                d.setPermissionGrantState(a, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.getOrDefault(false)
        }
        out += "BASTION: ${grant(c.packageName, OWN)} permission(s) granted."
        for ((pkg, perms) in COMPANIONS) {
            out += if (installed(c, pkg)) "$pkg: ${grant(pkg, perms)} permission(s) granted." else "$pkg: not installed."
        }
        runCatching {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
val alias = ComponentName(c.packageName, c.packageName + ".HomeAlias")
            c.packageManager.setComponentEnabledSetting(
                alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
            )
            d.addPersistentPreferredActivity(a, home, alias)
            out += "BASTION is the home screen."
        }.onFailure { out += "Could not set the home screen: ${it.message}" }
        runCatching {
            d.setLockTaskPackages(a, (listOf(c.packageName) + COMPANIONS.keys + EXTRA_KIOSK).toTypedArray())
        }
        Log.i(TAG, out.joinToString(" | "))
        return out
    }

    /** Settings and the file picker stay reachable in kiosk mode (pairing a radio, importing files). */
    private val EXTRA_KIOSK = listOf("com.android.settings", "com.google.android.documentsui", "com.android.documentsui")

    /** Installs every .apk in the kit folder silently. Needs the device-manager role. */
    fun installKit(c: Context): List<String> {
        if (!isOwner(c)) return listOf("BASTION is not this phone's device manager, so it cannot install apps by itself.")
        val apks = kitFolder(c).listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }?.sortedBy { it.name }.orEmpty()
        if (apks.isEmpty()) return listOf("No .apk files in ${kitFolder(c).absolutePath}.")
        return apks.map { f ->
            runCatching { install(c, f); "${f.name}: install started." }
                .getOrElse { "${f.name}: failed — ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) }
            }
            val done = PendingIntent.getBroadcast(
                c, id, Intent(c, InstallResult::class.java).putExtra("file", apk.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(done.intentSender)
        }
    }

    /**
     * Apps that keep their icon when "Hide other apps" is on, besides BASTION and
     * its companions: things the phone can't be operated without.
     */
    private val ESSENTIAL = setOf(
        "com.android.settings", "com.android.documentsui", "com.google.android.documentsui",
        "com.android.bluetooth", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
    )

    private fun hiddenPrefs(c: Context) = c.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Packages BASTION has hidden (so exactly those can be brought back). */
    fun hiddenApps(c: Context): Set<String> = hiddenPrefs(c).getStringSet("hidden", emptySet()).orEmpty()

    /**
     * Hides every app that has a launcher icon and is not BASTION, a
     * companion or an essential — browser, store, mail, video and so on. A
     * hidden app cannot run or update and uses no battery. Built-in apps are
     * hidden, not deleted (Android keeps them in a read-only area), which on
     * a dedicated device comes to the same thing. System parts without an
     * icon (keyboard, Bluetooth, the launcher that draws the recent-apps
     * screen) are never touched. Also blocks adding accounts.
     */
    fun hideOtherApps(c: Context): String {
        if (!isOwner(c)) return "BASTION is not this phone's device manager, so it cannot hide apps."
        val keep = ESSENTIAL + COMPANIONS.keys + c.packageName
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val withIcons = c.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
        val hidden = HashSet(hiddenApps(c))
        var count = 0
        for (pkg in withIcons - keep) {
            if (runCatching { dpm(c).setApplicationHidden(admin(c), pkg, true) }.getOrDefault(false)) {
                hidden += pkg; count++
            }
        }
        hiddenPrefs(c).edit().putStringSet("hidden", hidden).apply()
        runCatching { dpm(c).addUserRestriction(admin(c), android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS) }
        Log.i(TAG, "hid $count app(s)")
        return "Hid $count app(s). ${hidden.size} hidden in total; adding accounts is blocked."
    }

    /** Brings back every app BASTION hid and allows accounts again. */
    fun showHiddenApps(c: Context): String {
        if (!isOwner(c)) return "BASTION is not this phone's device manager."
        val hidden = hiddenApps(c)
        val restored = hidden.count { pkg ->
            runCatching { dpm(c).setApplicationHidden(admin(c), pkg, false) }.isSuccess
        }
        hiddenPrefs(c).edit().remove("hidden").apply()
        runCatching { dpm(c).clearUserRestriction(admin(c), android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS) }
        return "Brought back $restored app(s)."
    }

    /** Gives the role back, so the phone can be used normally again without a factory reset. */
    fun release(c: Context): String = if (!isOwner(c)) "BASTION is not the device manager." else runCatching {
        // Never leave apps hidden with nothing able to bring them back.
        showHiddenApps(c)
        dpm(c).clearPackagePersistentPreferredActivities(admin(c), c.packageName)
        c.packageManager.setComponentEnabledSetting(
            ComponentName(c.packageName, c.packageName + ".HomeAlias"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
        )
        @Suppress("DEPRECATION")
        dpm(c).clearDeviceOwnerApp(c.packageName)
        "BASTION is no longer the device manager. The phone behaves like an ordinary phone again."
    }.getOrElse { "Could not release: ${it.message}" }
}

/** Logs how each silent install ended. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.i("BastionDevice", "install ${intent.getStringExtra("file")}: " +
            (if (status == PackageInstaller.STATUS_SUCCESS) "ok" else "failed ($status) $msg"))
    }
}
