package com.bastion.terminal.security

import android.content.Context
import android.content.Intent
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional app PIN and **wipe PIN** (owner decision 2026-11-22).
 *
 * - App PIN: asked when BASTION opens.
 * - Wipe PIN: entered in the same box, it erases BASTION's private data
 *   (library, packs, mesh inbox, approved radios, TAK certificates,
 *   settings) and then opens BASTION as if nothing happened — an empty app,
 *   same PIN screen next time. It cannot be undone.
 * - Not erased: the AI model and offline reference (ZIM) files — public
 *   content, and large to replace in the field.
 *
 * PINs are never stored: only salted PBKDF2 hashes. This is a lock on the
 * app, not disk encryption — Android's own storage encryption (screen lock)
 * is what protects the files at rest.
 */
object AppLock {
    enum class Result { OPEN, WIPED, WRONG, LOCKED_OUT }

    private const val PREFS = "lock"
    private const val ITERATIONS = 120_000

    /** True once the right PIN was entered in this process (or no PIN is set). */
    @Volatile var unlocked = false
        private set
    private var wrongTries = 0
    private var lockedUntil = 0L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasAppPin(c: Context): Boolean = prefs(c).contains("app")
    fun hasWipePin(c: Context): Boolean = prefs(c).contains("wipe")

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun salt(c: Context): ByteArray {
        val p = prefs(c)
        p.getString("salt", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit().putString("salt", Base64.encodeToString(s, Base64.NO_WRAP)).apply()
        return s
    }

    fun validPin(pin: String): Boolean = pin.length in 4..12 && pin.all { it.isDigit() }

    fun setAppPin(c: Context, pin: String) {
        prefs(c).edit().putString("app", hash(pin, salt(c))).apply()
        unlocked = true
    }

    /** Returns false if it equals the app PIN (they must differ). */
    fun setWipePin(c: Context, pin: String): Boolean {
        val h = hash(pin, salt(c))
        if (h == prefs(c).getString("app", null)) return false
        prefs(c).edit().putString("wipe", h).apply()
        return true
    }

    fun clearPins(c: Context) {
        prefs(c).edit().clear().apply()
        unlocked = true
    }

    /** Checks without side effects (used by the settings dialogs; never wipes). */
    fun checkAppPin(c: Context, pin: String): Boolean = hash(pin, salt(c)) == prefs(c).getString("app", null)

    fun isWipePin(c: Context, pin: String): Boolean = hash(pin, salt(c)) == prefs(c).getString("wipe", null)

    fun clearWipePin(c: Context) = prefs(c).edit().remove("wipe").apply()

    /** Checks a PIN typed on the lock screen. A wipe PIN erases and reports [Result.WIPED]. */
    fun submit(c: Context, pin: String, now: Long = System.currentTimeMillis()): Result {
        if (now < lockedUntil) return Result.LOCKED_OUT
        val p = prefs(c)
        val h = hash(pin, salt(c))
        return when (h) {
            p.getString("app", null) -> { wrongTries = 0; unlocked = true; Result.OPEN }
            p.getString("wipe", "\u0000") -> { wrongTries = 0; wipe(c); unlocked = true; Result.WIPED }
            else -> {
                wrongTries++
                // After 5 wrong tries: 30 s, then doubling. Slows guessing; does not wipe.
                if (wrongTries >= 5) lockedUntil = now + 30_000L * (1L shl minOf(wrongTries - 5, 6))
                Result.WRONG
            }
        }
    }

    fun lockedForSeconds(now: Long = System.currentTimeMillis()): Long = ((lockedUntil - now + 999) / 1000).coerceAtLeast(0)

    /**
     * What a wipe **keeps**; everything else BASTION stored is erased. Keeping a short
     * keep-list (instead of a list of things to delete) means new private data added later
     * is wiped by default.
     */
    /** Mesh messages timestamped at or before this are ignored (0 = never wiped). */
    fun wipedAt(c: Context): Long = prefs(c).getLong("wiped_at", 0L)

    val KEEP_FILE_DIRS = setOf("models")
    val KEEP_PREFS = setOf("lock", "local_ai")

    /** Erases BASTION's private data. Keeps the PINs, the AI model and ZIM files. */
    fun wipe(c: Context) {
        val app = c.applicationContext
        app.databaseList().forEach { app.deleteDatabase(it) }
        app.filesDir.listFiles()?.filterNot { it.name in KEEP_FILE_DIRS }?.forEach { it.deleteRecursively() }
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".xml")
            if (name !in KEEP_PREFS) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                f.delete()
            }
        }
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Messages announced before this moment must never be read back in: a Meshtastic
        // notification still on screen was re-ingested after a wipe (found on emulator).
        prefs(c).edit().putLong("wiped_at", System.currentTimeMillis()).commit()
    }

    /**
     * After a wipe the open database handles and in-memory lists still hold
     * old data, so the process is restarted. A one-time flag opens the app
     * straight away — to an onlooker the PIN simply worked.
     */
    fun restartAfterWipe(c: Context) {
        prefs(c).edit().putBoolean("open_once", true).commit()
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) c.startActivity(i)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Called at app start: should the PIN screen be shown? */
    fun needsPin(c: Context): Boolean {
        if (unlocked || !hasAppPin(c)) return false
        val p = prefs(c)
        if (p.getBoolean("open_once", false)) {
            p.edit().remove("open_once").apply()
            unlocked = true
            return false
        }
        return true
    }
}
