package com.intercom.app.location

/**
 * The words that go with a distance. Pure functions, so what the rider is told is tested,
 * and the screen only has to draw it.
 */
object DistanceText {

    /** "240 m", "1.3 km" or "< 20 m" when the two fixes cannot tell the riders apart. */
    fun distance(d: RiderDistance, unit: DistanceUnit): String =
        DistanceFormat.format(d.meters, unit, d.uncertaintyM).text

    /**
     * Where they are relative to the way we are heading, or null when we cannot say
     * (stationary, no heading yet, or too close to tell).
     */
    fun side(d: RiderDistance): String? = when (d.side) {
        Side.Ahead -> "ahead"
        Side.Behind -> "behind"
        Side.Beside -> if ((d.relativeDeg ?: 0.0) < 0) "to your left" else "to your right"
        Side.Unknown -> null
    }

    /** Compass direction for when there is no heading to point an arrow with. */
    fun compass(d: RiderDistance): String = Geo.cardinal(d.bearingDeg)

    /** "12 s ago", "3 min ago". */
    fun ago(seconds: Long): String = when {
        seconds < 60 -> "$seconds s ago"
        seconds < 3_600 -> "${seconds / 60} min ago"
        else -> "over an hour ago"
    }

    /** Shown once a position is a few seconds old; the value itself is greyed when [RiderDistance.stale]. */
    const val SHOW_AGE_AFTER_S = 5L

    fun lastSeen(d: RiderDistance): String? =
        if (d.ownStale) null
        else if (d.ageSeconds >= SHOW_AGE_AFTER_S) "last seen ${ago(d.ageSeconds)}" else null

    /** One line under the number: "ahead · last seen 12 s ago". Null if there is nothing to add. */
    fun detail(d: RiderDistance): String? {
        val parts = listOfNotNull(
            if (d.relativeDeg == null) "to the ${compass(d)}" else side(d),
            lastSeen(d),
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** For a screen reader: everything on the chip, in a sentence. */
    fun spoken(name: String?, d: RiderDistance, unit: DistanceUnit): String = buildString {
        append(name ?: "Rider").append(", ")
        val text = distance(d, unit)
        append(if (text.startsWith("<")) "less than ${text.removePrefix("< ")}" else text).append(" away")
        detail(d)?.let { append(", ").append(it.replace(" · ", ", ")) }
        if (d.ownStale) append(", your GPS has no recent fix")
        else if (d.stale) append(", position is out of date")
    }

    /** What to tell the rider about the GPS itself, or null when it needs no comment. */
    fun gpsNote(state: LocationState, haveDistance: Boolean, ownStale: Boolean): String? = when (state) {
        LocationState.Off -> null
        LocationState.Searching -> "Looking for a GPS fix…"
        LocationState.NoPermission -> "Precise location is not allowed, so nothing is shared."
        LocationState.Disabled -> "Location is switched off on this phone."
        LocationState.Unavailable -> "This phone has no GPS to share."
        LocationState.Active ->
            if (ownStale) "Your GPS signal is weak: distances may be out of date."
            else if (!haveDistance) "Waiting for the other rider's position…"
            else null
    }
}
