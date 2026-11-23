package com.intercom.app.location

import com.intercom.app.protocol.PositionReport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * This phone's own latest fix.
 *
 * @property headingDeg direction of travel, or null while too slow for a heading to mean
 *   anything (see [Geo.MIN_HEADING_SPEED_MPS])
 * @property stampMicros the monotonic clock ([System.nanoTime] / 1000) at the moment the
 *   fix was taken, not when it was delivered; this is the time the buddy converts with the
 *   link's clock offset
 */
data class OwnFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val headingDeg: Double?,
    val accuracyM: Double,
    val stampMicros: Long,
)

/**
 * What the screen needs to know about one other rider.
 *
 * @property meters distance, smoothed
 * @property uncertaintyM combined accuracy of the two fixes
 * @property bearingDeg true bearing from me to them
 * @property relativeDeg the same bearing relative to my direction of travel (negative =
 *   left), or null while I am too slow for a heading
 * @property ageSeconds time since their last report arrived
 * @property stale their report is older than [PositionPolicy.STALE_AFTER_MS], or my own
 *   fix is: either way the number is shown greyed out
 * @property ownStale my own GPS has gone quiet, so the problem is on this phone
 */
data class RiderDistance(
    val meters: Double,
    val uncertaintyM: Double,
    val bearingDeg: Double,
    val relativeDeg: Double?,
    val side: Side,
    val peerSpeedMps: Double,
    val ageSeconds: Long,
    val stale: Boolean,
    val ownStale: Boolean,
)

/** Conversions between a fix and the 22-byte wire report. */
object PositionCodec {
    /** Most plausible time a fix can spend in flight before it is not worth trusting. */
    const val MAX_PLAUSIBLE_AGE_MS = 30_000L

    fun toReport(fix: OwnFix): PositionReport = PositionReport(
        latE7 = (fix.lat * 1e7).roundToInt(),
        lonE7 = (fix.lon * 1e7).roundToInt(),
        speedCms = (fix.speedMps * 100).roundToInt().coerceIn(0, 0xFFFF),
        headingDeci = ((fix.headingDeg ?: 0.0) * 10).roundToInt().mod(3600),
        accuracyM = Math.ceil(fix.accuracyM).toInt().coerceIn(0, 0xFFFF),
        timestampMs = fix.stampMicros / 1000L,
    )

    /**
     * The monotonic stamp for a fix taken at [fixElapsedNanos] on the `elapsedRealtimeNanos`
     * clock (what Android stamps a location with), expressed on the [System.nanoTime] clock
     * the link's offset is measured on. The stamp is when the fix was taken, not when it was
     * delivered, so a late callback does not make an old position look new.
     */
    fun fixStampMicros(nanoTimeNow: Long, elapsedNowNanos: Long, fixElapsedNanos: Long): Long =
        (nanoTimeNow - (elapsedNowNanos - fixElapsedNanos).coerceAtLeast(0L)) / 1000L

    /** Used when the phone gives a fix with no accuracy figure: honest, not optimistic. */
    const val UNKNOWN_ACCURACY_M = 50.0

    /**
     * An [OwnFix] from the raw numbers of an Android `Location`. A bearing is kept only when
     * moving fast enough for it to mean anything (a stationary GPS reports a random one).
     */
    fun fromLocation(
        latitude: Double,
        longitude: Double,
        speedMps: Double,
        bearingDeg: Double?,
        accuracyM: Double,
        nanoTimeNow: Long,
        elapsedNowNanos: Long,
        fixElapsedNanos: Long,
    ): OwnFix {
        val speed = if (speedMps.isNaN()) 0.0 else speedMps.coerceAtLeast(0.0)
        val heading = bearingDeg
            ?.takeIf { !it.isNaN() && speed >= Geo.MIN_HEADING_SPEED_MPS }
            ?.let { Geo.normalize360(it) }
        return OwnFix(
            lat = latitude,
            lon = longitude,
            speedMps = speed,
            headingDeg = heading,
            accuracyM = if (accuracyM.isNaN()) UNKNOWN_ACCURACY_M else accuracyM.coerceAtLeast(0.0),
            stampMicros = fixStampMicros(nanoTimeNow, elapsedNowNanos, fixElapsedNanos),
        )
    }

    fun latDeg(r: PositionReport): Double = r.latE7 / 1e7
    fun lonDeg(r: PositionReport): Double = r.lonE7 / 1e7
    fun speedMps(r: PositionReport): Double = r.speedCms / 100.0

    /** Heading in degrees, or null when the sender was too slow for it to be meaningful. */
    fun headingDeg(r: PositionReport): Double? =
        if (speedMps(r) >= Geo.MIN_HEADING_SPEED_MPS) (r.headingDeci / 10.0).coerceIn(0.0, 359.9) else null

    fun isUsable(r: PositionReport): Boolean =
        Geo.isValid(latDeg(r), lonDeg(r)) && r.accuracyM <= PositionPolicy.MAX_USABLE_ACCURACY_M

    /**
     * How old the fix already was when it arrived, using the link's clock offset
     * (`peerTimestamp - offset` is the local monotonic time). Null when the arithmetic
     * gives something implausible, which is what an unmeasured offset produces; the caller
     * then treats the fix as fresh at arrival.
     */
    fun ageMs(r: PositionReport, clockOffsetMicros: Long, nowMicros: Long): Long? {
        val localMicros = r.timestampMs * 1000L - clockOffsetMicros
        val age = (nowMicros - localMicros) / 1000L
        return if (age in 0..MAX_PLAUSIBLE_AGE_MS) age else null
    }
}

/**
 * Keeps everyone's latest position, and turns it into distance, direction and freshness
 * relative to this phone.
 *
 * Riders are keyed by an integer: [BUDDY] in a two-rider call, the sender ID in a group.
 *
 * Thread-safe without a lock: each rider's state is one immutable [Track] replaced
 * atomically in a [ConcurrentHashMap], and this phone's own fix is an [AtomicReference]. The
 * receive thread, the location callback and the screen's timer all use it.
 *
 * The distance is smoothed (an exponential average) because two noisy fixes jitter by tens
 * of metres from one report to the next, and a number that flickers between 180 and 230 is
 * worse than one that settles. A real change (the rider jumped far, or the average is
 * long out of date) skips the smoothing so it never lags a genuine move.
 */
class PositionTracker(private val now: () -> Long = System::currentTimeMillis) {

    private class Track(
        val report: PositionReport,
        val receivedAt: Long,
        val smoothedM: Double?,
        val smoothedAt: Long,
    )

    private val tracks = ConcurrentHashMap<Int, Track>()
    private val ownFix = AtomicReference<OwnFix?>(null)
    private val ownAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** Records this phone's fix. A fix that is invalid or too inaccurate is ignored (false). */
    fun setOwn(fix: OwnFix): Boolean {
        if (!Geo.isValid(fix.lat, fix.lon) || fix.accuracyM > PositionPolicy.MAX_USABLE_ACCURACY_M) return false
        ownFix.set(fix)
        ownAt.set(now())
        // Everyone's distance changed: refresh the smoothing for each.
        val t = now()
        for (key in tracks.keys) tracks.computeIfPresent(key) { _, old -> refreshed(old, fix, t) }
        return true
    }

    fun own(): OwnFix? = ownFix.get()

    /**
     * A report arrived from [key]. [fixAgeMs] is how old the fix already was (from
     * [PositionCodec.ageMs]); it makes the rider look as old as their fix really is, so a
     * report that sat in a queue is not mistaken for a fresh one. Returns false if the
     * report is unusable.
     */
    fun onReport(key: Int, report: PositionReport, fixAgeMs: Long = 0L): Boolean {
        if (!PositionCodec.isUsable(report)) return false
        val t = now()
        val own = ownFix.get()
        val receivedAt = t - fixAgeMs.coerceAtLeast(0L)
        tracks.compute(key) { _, old ->
            val raw = own?.let { rawDistance(it, report) }
            val smoothed = if (raw == null) null else nextSmoothed(old?.smoothedM, old?.smoothedAt ?: 0L, raw, t)
            Track(report, receivedAt, smoothed, if (raw == null) 0L else t)
        }
        return true
    }

    fun forget(key: Int) { tracks.remove(key) }

    fun clear() {
        tracks.clear()
        ownFix.set(null)
        ownAt.set(0L)
    }

    /** Everyone with a report, relative to this phone. Empty until this phone has a fix. */
    fun snapshot(): Map<Int, RiderDistance> {
        val own = ownFix.get() ?: return emptyMap()
        val t = now()
        val ownStale = PositionPolicy.isStale(t - ownAt.get())
        val out = HashMap<Int, RiderDistance>()
        for ((key, track) in tracks) out[key] = describe(own, track, t, ownStale)
        return out
    }

    fun distanceOf(key: Int): RiderDistance? = snapshot()[key]

    // ------------------------------------------------------------ maths

    private fun refreshed(old: Track, own: OwnFix, t: Long): Track {
        val raw = rawDistance(own, old.report)
        return Track(old.report, old.receivedAt, nextSmoothed(old.smoothedM, old.smoothedAt, raw, t), t)
    }

    private fun rawDistance(own: OwnFix, r: PositionReport): Double =
        Geo.distanceM(own.lat, own.lon, PositionCodec.latDeg(r), PositionCodec.lonDeg(r))

    private fun nextSmoothed(prev: Double?, prevAt: Long, raw: Double, t: Long): Double {
        if (prev == null || t - prevAt > RESET_AFTER_MS) return raw
        if (abs(raw - prev) > maxOf(JUMP_MIN_M, prev * JUMP_FRACTION)) return raw
        return prev + ALPHA * (raw - prev)
    }

    private fun describe(own: OwnFix, track: Track, t: Long, ownStale: Boolean): RiderDistance {
        val r = track.report
        val lat = PositionCodec.latDeg(r)
        val lon = PositionCodec.lonDeg(r)
        val raw = Geo.distanceM(own.lat, own.lon, lat, lon)
        val meters = track.smoothedM ?: raw
        val uncertainty = hypot(own.accuracyM, r.accuracyM.toDouble())
        val bearing = Geo.bearingDeg(own.lat, own.lon, lat, lon)
        val rel = own.headingDeg?.let { Geo.relativeBearing(bearing, it) }
        // Too close to tell which side they are on: the fixes overlap.
        val side = when {
            rel == null -> Side.Unknown
            meters < maxOf(uncertainty, DistanceFormat.MIN_PRECISE_M) -> Side.Unknown
            else -> Geo.sideOf(rel)
        }
        val ageMs = (t - track.receivedAt).coerceAtLeast(0L)
        return RiderDistance(
            meters = meters,
            uncertaintyM = uncertainty,
            bearingDeg = bearing,
            relativeDeg = rel,
            side = side,
            peerSpeedMps = PositionCodec.speedMps(r),
            ageSeconds = ageMs / 1000L,
            stale = PositionPolicy.isStale(ageMs) || ownStale,
            ownStale = ownStale,
        )
    }

    companion object {
        /** Weight of the newest sample in the average. */
        const val ALPHA = 0.4

        /** An average this old is thrown away. */
        const val RESET_AFTER_MS = 15_000L

        /** A change bigger than this (or half the distance) is a real move, not noise. */
        const val JUMP_MIN_M = 150.0
        const val JUMP_FRACTION = 0.5

        /** Key for the other rider in a two-rider call. */
        const val BUDDY = 0
    }
}
