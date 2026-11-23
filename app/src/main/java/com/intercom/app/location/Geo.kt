package com.intercom.app.location

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a rider is relative to the direction I am travelling. */
enum class Side { Ahead, Behind, Beside, Unknown }

/**
 * Plain geometry on a sphere, no Android. Good to a fraction of a percent, which is far
 * inside what a phone's GPS can tell you about two riders a few hundred metres apart.
 */
object Geo {
    /** Mean Earth radius, metres (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Below this ground speed a GPS heading is noise, so it is not used. */
    const val MIN_HEADING_SPEED_MPS = 1.5

    /** Within this of straight ahead / straight behind counts as ahead / behind. */
    const val AHEAD_BEHIND_HALF_ANGLE = 60.0

    fun isValid(latDeg: Double, lonDeg: Double): Boolean =
        !latDeg.isNaN() && !lonDeg.isNaN() && latDeg in -90.0..90.0 && lonDeg in -180.0..180.0

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val s1 = sin(dp / 2)
        val s2 = sin(dl / 2)
        val h = (s1 * s1 + cos(p1) * cos(p2) * s2 * s2).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Initial true bearing from point 1 to point 2, degrees clockwise from north, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize360(Math.toDegrees(atan2(y, x)))
    }

    /** [deg] folded into 0 <= x < 360. */
    fun normalize360(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** [deg] folded into -180 < x <= 180: negative is to the left, positive to the right. */
    fun signedDelta(deg: Double): Double {
        val r = normalize360(deg)
        return if (r > 180.0) r - 360.0 else r
    }

    /** Where a rider at [bearingDeg] appears when I am heading [headingDeg]. */
    fun relativeBearing(bearingDeg: Double, headingDeg: Double): Double =
        signedDelta(bearingDeg - headingDeg)

    fun sideOf(relativeDeg: Double): Side {
        val a = kotlin.math.abs(signedDelta(relativeDeg))
        return when {
            a <= AHEAD_BEHIND_HALF_ANGLE -> Side.Ahead
            a >= 180.0 - AHEAD_BEHIND_HALF_ANGLE -> Side.Behind
            else -> Side.Beside
        }
    }

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Eight-point compass name for a bearing. */
    fun cardinal(deg: Double): String = COMPASS[((normalize360(deg) + 22.5) / 45.0).toInt() % 8]

    /** Radians for a heading, for drawing. */
    fun toRadians(deg: Double): Double = deg * PI / 180.0
}

enum class DistanceUnit(val label: String) {
    Kilometres("km"),
    Miles("mi");

    companion object {
        private val IMPERIAL = setOf("US", "GB", "LR", "MM")

        /** Miles in the few countries that drive on them, kilometres everywhere else. */
        fun defaultFor(country: String?): DistanceUnit =
            if (country != null && country.uppercase(Locale.ROOT) in IMPERIAL) Miles else Kilometres
    }
}

/** A distance ready to show, and whether it is a number or a "less than" floor. */
data class FormattedDistance(val text: String, val precise: Boolean)

object DistanceFormat {
    /** A distance under this is never shown as a number, whatever the GPS claims. */
    const val MIN_PRECISE_M = 20.0

    private const val FEET_PER_METRE = 3.28084
    private const val METRES_PER_MILE = 1609.344

    /**
     * [meters] as short text. [uncertaintyM] is the combined accuracy of the two fixes;
     * a distance smaller than that (or than [MIN_PRECISE_M]) could be the same spot, so it
     * is shown as "< N m" with N rounded up to 10, not as a number it does not have.
     */
    fun format(
        meters: Double,
        unit: DistanceUnit,
        uncertaintyM: Double = 0.0,
        locale: Locale = Locale.getDefault(),
    ): FormattedDistance {
        val floorM = maxOf(MIN_PRECISE_M, uncertaintyM)
        if (meters < floorM) {
            val text = when (unit) {
                DistanceUnit.Kilometres -> "< ${ceilTo10(floorM)} m"
                DistanceUnit.Miles -> "< ${ceilTo10(floorM * FEET_PER_METRE)} ft"
            }
            return FormattedDistance(text, precise = false)
        }
        val text = when (unit) {
            DistanceUnit.Kilometres -> metric(meters, locale)
            DistanceUnit.Miles -> imperial(meters, locale)
        }
        return FormattedDistance(text, precise = true)
    }

    private fun metric(m: Double, locale: Locale): String = when {
        m < 100.0 -> "${roundTo(m, 5)} m"
        m < 995.0 -> "${roundTo(m, 10)} m"
        m < 9_950.0 -> String.format(locale, "%.1f km", m / 1000.0)
        else -> "${(m / 1000.0).roundToInt()} km"
    }

    private fun imperial(m: Double, locale: Locale): String {
        val feet = m * FEET_PER_METRE
        return when {
            feet < 995.0 -> "${roundTo(feet, 10)} ft"
            m < 9.95 * METRES_PER_MILE -> String.format(locale, "%.1f mi", m / METRES_PER_MILE)
            else -> "${(m / METRES_PER_MILE).roundToInt()} mi"
        }
    }

    private fun roundTo(v: Double, step: Int): Int = (v / step).roundToInt() * step
    private fun ceilTo10(v: Double): Int = (ceil(v / 10.0) * 10).toInt()
}
