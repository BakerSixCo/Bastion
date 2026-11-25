package com.bastion.terminal.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * This phone's position from GPS (owner decision 2026-11-22). Uses Android's
 * own LocationManager — satellites only, no Google location service and no
 * network lookup. The position stays in memory; it is used for distances and
 * bearings and is never written to disk or sent anywhere by this class.
 */
@SuppressLint("MissingPermission")   // every request is made only after hasPermission()
class LocationSource private constructor(private val app: Context) {

    private val _fix = MutableStateFlow<Nav.Fix?>(null)
    val fix: StateFlow<Nav.Fix?> = _fix

    private val listener = LocationListener { l -> publish(l) }
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val gps = GpsSubscription(
        request = { fast ->
            val m = lm ?: error("no location service")
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (fast) 1_000L else 10_000L, if (fast) 0f else 10f, listener, Looper.getMainLooper())
        },
        remove = { lm?.removeUpdates(listener) },
    )

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun publish(l: Location) {
        _fix.value = Nav.Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time, l.elapsedRealtimeNanos / 1_000_000L)
    }

    /** Starts GPS updates if the permission is granted. Safe to call repeatedly. */
    fun start() {
        if (gps.listening || !hasPermission()) return
        val m = lm ?: return
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::publish) }
        gps.start()
    }

    /** Turn-by-turn needs a fix every second; otherwise every 10 s / 10 m saves battery. */
    fun fast(on: Boolean) {
        if (!gps.listening) start()
        gps.rate(on)
    }

    fun stop() = gps.stop()

    companion object {
        @Volatile private var inst: LocationSource? = null
        fun get(context: Context): LocationSource =
            inst ?: synchronized(this) { inst ?: LocationSource(context.applicationContext).also { inst = it } }
    }
}

/**
 * Whether GPS updates are registered, kept true only while a registration really holds (pure logic,
 * unit-tested). A rate switch removes the old request before making the new one; if the new one fails
 * (permission withdrawn, provider off) nothing is registered any more, so [listening] goes false and a
 * later [start] registers again instead of returning early.
 */
internal class GpsSubscription(private val request: (fast: Boolean) -> Unit, private val remove: () -> Unit) {
    var listening = false; private set

    fun start(): Boolean {
        if (listening) return true
        listening = runCatching { request(false) }.isSuccess
        return listening
    }

    fun rate(fast: Boolean) {
        if (!listening) return
        runCatching { remove(); request(fast) }.onFailure {
            listening = false
            runCatching { remove() }
        }
    }

    fun stop() {
        if (!listening) return
        runCatching { remove() }
        listening = false
    }
}
