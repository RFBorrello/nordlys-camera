package com.nordlys.camera.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.Manifest

/**
 * Keeps a recent position while the viewfinder is open so photos can be
 * geotagged ("location info" in settings). Uses the platform location
 * manager directly; nothing runs while the camera is in the background.
 */
class LocationTagger(context: Context, private val onFix: (Location?) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var best: Location? = null
    private val listener = LocationListener { offer(it) }

    fun granted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!granted()) return
        for (p in PROVIDERS) runCatching { manager.getLastKnownLocation(p)?.let(::offer) }
        for (p in PROVIDERS) runCatching {
            if (manager.isProviderEnabled(p)) manager.requestLocationUpdates(p, 30_000L, 10f, listener, Looper.getMainLooper())
        }
    }

    fun stop() {
        runCatching { manager.removeUpdates(listener) }
        best = null
        onFix(null)
    }

    private fun offer(l: Location) {
        val cur = best
        val stale = System.currentTimeMillis() - l.time > MAX_AGE_MS
        if (stale) return
        // a newer fix wins unless it is much less accurate than a recent one
        if (cur == null || l.time - cur.time > 60_000L || l.accuracy <= cur.accuracy * 1.5f) {
            best = l
            onFix(l)
        }
    }

    private companion object {
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        const val MAX_AGE_MS = 30 * 60_000L
    }
}
