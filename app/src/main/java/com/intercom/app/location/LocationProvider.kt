package com.intercom.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** What the phone's GPS is doing, for the call screen. */
enum class LocationState {
    /** Sharing is off: nothing is being read. */
    Off,

    /** Asked for fixes, none yet (a cold GPS takes a while, and is slower indoors). */
    Searching,

    /** Fixes are arriving. */
    Active,

    /** Precise location has not been granted. */
    NoPermission,

    /** The phone's own location switch is off. */
    Disabled,

    /** No provider could be started on this phone. */
    Unavailable,
}

/**
 * Where this phone's fixes come from. An interface so the call logic does not depend on
 * Android; the real one is [AndroidLocationProvider].
 */
interface LocationSource {
    /** Begin delivering fixes about every [intervalMs]. Safe to call again with a new rate. */
    fun start(intervalMs: Long, onFix: (OwnFix) -> Unit, onState: (LocationState) -> Unit)

    /** Change the rate without restarting. */
    fun setInterval(intervalMs: Long)

    /** Stop and release everything. Safe to call twice. */
    fun stop()
}

/**
 * Phone GPS, through Google's fused provider when Play services is there and through the
 * platform [LocationManager] when it is not (or when the fused client fails to start).
 *
 * Reads only while started, and the call starts it only while "Share my location" is on and
 * a call is up, and stops it when the call ends. Nothing here touches the network: the
 * fused provider and the GPS chip both work with no internet.
 *
 * Callbacks come on the main looper. They only update a tracker and enqueue a frame
 * (neither blocks), so nothing here can hold up audio.
 */
class AndroidLocationProvider(context: Context) : LocationSource {

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var onFix: ((OwnFix) -> Unit)? = null
    private var onState: ((LocationState) -> Unit)? = null
    /** The rate the GPS is registered at, and the rate the policy would like now. */
    private var intervalMs = PositionPolicy.MEDIUM_MS
    private var wantedMs = PositionPolicy.MEDIUM_MS
    private var registeredAt = 0L
    private var running = false
    private var usingFused = false
    private var gotFix = false

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(app) }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) deliver(l)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                gotFix = false
                state(if (providerSwitchOn()) LocationState.Searching else LocationState.Disabled)
            }
        }
    }

    // Overrides all three legacy methods: on Android 10 (API 29) they are abstract.
    private val platformListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(location)

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            if (!gotFix) state(LocationState.Searching)
        }

        override fun onProviderDisabled(provider: String) {
            gotFix = false
            state(LocationState.Disabled)
        }
    }

    @Synchronized
    override fun start(intervalMs: Long, onFix: (OwnFix) -> Unit, onState: (LocationState) -> Unit) {
        this.onFix = onFix
        this.onState = onState
        this.wantedMs = intervalMs
        if (running) { applyWanted(); return }
        this.intervalMs = intervalMs
        registeredAt = SystemClock.elapsedRealtime()
        running = true
        gotFix = false
        state(LocationState.Searching)
        if (fusedAvailable() && startFused()) return
        startPlatform()
    }

    @Synchronized
    override fun setInterval(intervalMs: Long) {
        wantedMs = intervalMs
        applyWanted()
    }

    /**
     * Re-registering is how both providers change their rate, and it restarts their
     * bookkeeping, so it is done at most once every [MIN_REREGISTER_MS]: a pair of riders
     * hovering around 100 m apart would otherwise flip the rate on every fix. A change that
     * had to wait is picked up by the next fix.
     */
    private fun applyWanted() {
        if (!running || wantedMs == intervalMs) return
        val now = SystemClock.elapsedRealtime()
        if (now - registeredAt < MIN_REREGISTER_MS) return
        intervalMs = wantedMs
        registeredAt = now
        if (usingFused) { removeFused(); if (!startFused()) startPlatform() }
        else { removePlatform(); startPlatform() }
    }

    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        removeFused()
        removePlatform()
        gotFix = false
        onFix = null
        state(LocationState.Off)
        onState = null
    }

    // ------------------------------------------------------------ fused

    private fun fusedAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    @SuppressLint("MissingPermission") // the call checks Permissions.hasLocation before starting
    private fun startFused(): Boolean = try {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
            .addOnFailureListener { t ->
                Log.w(TAG, "fused provider refused, using the platform one", t)
                synchronized(this) {
                    if (running && usingFused) { usingFused = false; startPlatform() }
                }
            }
        usingFused = true
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "no location permission", e)
        state(LocationState.NoPermission)
        usingFused = false
        // Not falling back: the platform provider would hit the same missing permission.
        running = false
        true
    } catch (t: Throwable) {
        Log.w(TAG, "fused provider failed to start", t)
        usingFused = false
        false
    }

    private fun removeFused() {
        if (!usingFused) return
        usingFused = false
        runCatching { fused.removeLocationUpdates(fusedCallback) }
    }

    // ------------------------------------------------------------ platform

    @SuppressLint("MissingPermission") // the call checks Permissions.hasLocation before starting
    private fun startPlatform() {
        if (!providerSwitchOn()) { state(LocationState.Disabled); /* keep listening: it may come on */ }
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, intervalMs, 0f, platformListener, Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "no location permission", e)
            running = false
            state(LocationState.NoPermission)
        } catch (e: IllegalArgumentException) {
            // No GPS provider on this phone.
            Log.w(TAG, "no GPS provider", e)
            running = false
            state(LocationState.Unavailable)
        }
    }

    private fun removePlatform() {
        runCatching { manager.removeUpdates(platformListener) }
    }

    private fun providerSwitchOn(): Boolean =
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    // ------------------------------------------------------------ results

    private fun deliver(l: Location) {
        val fix = PositionCodec.fromLocation(
            latitude = l.latitude,
            longitude = l.longitude,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else 0.0,
            bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
            accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else PositionCodec.UNKNOWN_ACCURACY_M,
            nanoTimeNow = System.nanoTime(),
            elapsedNowNanos = SystemClock.elapsedRealtimeNanos(),
            fixElapsedNanos = l.elapsedRealtimeNanos,
        )
        gotFix = true
        state(LocationState.Active)
        onFix?.invoke(fix)
        synchronized(this) { applyWanted() }
    }

    private var lastState: LocationState? = null

    private fun state(s: LocationState) {
        if (s == lastState) return
        lastState = s
        onState?.invoke(s)
    }

    private companion object {
        const val TAG = "LocationProvider"
        const val MIN_REREGISTER_MS = 10_000L
    }
}
