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
class LocationSource private constructor(private val app: Context) {

    private val _fix = MutableStateFlow<Nav.Fix?>(null)
    val fix: StateFlow<Nav.Fix?> = _fix

    private var listening = false
    private val listener = LocationListener { l -> publish(l) }

    fun hasPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun publish(l: Location) {
        _fix.value = Nav.Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time)
    }

    /** Starts GPS updates if the permission is granted. Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !hasPermission()) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::publish)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 10f, listener, Looper.getMainLooper())
            listening = true
        }
    }

    fun stop() {
        if (!listening) return
        (app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(listener)
        listening = false
    }

    companion object {
        @Volatile private var inst: LocationSource? = null
        fun get(context: Context): LocationSource =
            inst ?: synchronized(this) { inst ?: LocationSource(context.applicationContext).also { inst = it } }
    }
}
