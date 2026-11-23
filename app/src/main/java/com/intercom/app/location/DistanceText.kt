package com.intercom.app.location

/**
 * The words that go with a distance, kept apart from the logic that decides which words apply.
 * The English set below is the default and what the unit tests read; the screens pass a set
 * backed by string resources so the same sentences come out in the rider's language.
 */
interface DistanceWords {
    val ahead: String
    val behind: String
    val toYourLeft: String
    val toYourRight: String
    fun toThe(compass: String): String
    fun secondsAgo(s: Long): String
    fun minutesAgo(m: Long): String
    val overAnHourAgo: String
    fun lastSeen(ago: String): String
    val rider: String
    fun away(distance: String, lessThan: Boolean): String
    val noRecentFix: String
    val outOfDate: String
    val searchingGps: String
    val noPermission: String
    val locationOff: String
    val noGps: String
    val weakGps: String
    val waitingForOther: String
}

object EnglishDistanceWords : DistanceWords {
    override val ahead = "ahead"
    override val behind = "behind"
    override val toYourLeft = "to your left"
    override val toYourRight = "to your right"
    override fun toThe(compass: String) = "to the $compass"
    override fun secondsAgo(s: Long) = "$s s ago"
    override fun minutesAgo(m: Long) = "$m min ago"
    override val overAnHourAgo = "over an hour ago"
    override fun lastSeen(ago: String) = "last seen $ago"
    override val rider = "Rider"
    override fun away(distance: String, lessThan: Boolean) =
        if (lessThan) "less than $distance away" else "$distance away"
    override val noRecentFix = "your GPS has no recent fix"
    override val outOfDate = "position is out of date"
    override val searchingGps = "Looking for a GPS fix…"
    override val noPermission = "Precise location is not allowed, so nothing is shared."
    override val locationOff = "Location is switched off on this phone."
    override val noGps = "This phone has no GPS to share."
    override val weakGps = "Your GPS signal is weak: distances may be out of date."
    override val waitingForOther = "Waiting for the other rider's position…"
}

/**
 * Pure functions, so what the rider is told is tested, and the screen only has to draw it.
 */
object DistanceText {

    /** "240 m", "1.3 km" or "< 20 m" when the two fixes cannot tell the riders apart. */
    fun distance(d: RiderDistance, unit: DistanceUnit): String =
        DistanceFormat.format(d.meters, unit, d.uncertaintyM).text

    /**
     * Where they are relative to the way we are heading, or null when we cannot say
     * (stationary, no heading yet, or too close to tell).
     */
    fun side(d: RiderDistance, w: DistanceWords = EnglishDistanceWords): String? = when (d.side) {
        Side.Ahead -> w.ahead
        Side.Behind -> w.behind
        Side.Beside -> if ((d.relativeDeg ?: 0.0) < 0) w.toYourLeft else w.toYourRight
        Side.Unknown -> null
    }

    /** Compass direction for when there is no heading to point an arrow with. */
    fun compass(d: RiderDistance): String = Geo.cardinal(d.bearingDeg)

    /** "12 s ago", "3 min ago". */
    fun ago(seconds: Long, w: DistanceWords = EnglishDistanceWords): String = when {
        seconds < 60 -> w.secondsAgo(seconds)
        seconds < 3_600 -> w.minutesAgo(seconds / 60)
        else -> w.overAnHourAgo
    }

    /** Shown once a position is a few seconds old; the value itself is greyed when [RiderDistance.stale]. */
    const val SHOW_AGE_AFTER_S = 5L

    fun lastSeen(d: RiderDistance, w: DistanceWords = EnglishDistanceWords): String? =
        if (d.ownStale) null
        else if (d.ageSeconds >= SHOW_AGE_AFTER_S) w.lastSeen(ago(d.ageSeconds, w)) else null

    /** One line under the number: "ahead · last seen 12 s ago". Null if there is nothing to add. */
    fun detail(d: RiderDistance, w: DistanceWords = EnglishDistanceWords): String? {
        val parts = listOfNotNull(
            if (d.relativeDeg == null) w.toThe(compass(d)) else side(d, w),
            lastSeen(d, w),
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** For a screen reader: everything on the chip, in a sentence. */
    fun spoken(
        name: String?,
        d: RiderDistance,
        unit: DistanceUnit,
        w: DistanceWords = EnglishDistanceWords,
    ): String = buildString {
        append(name ?: w.rider).append(", ")
        val text = distance(d, unit)
        append(
            if (text.startsWith("<")) w.away(text.removePrefix("< "), lessThan = true)
            else w.away(text, lessThan = false)
        )
        detail(d, w)?.let { append(", ").append(it.replace(" · ", ", ")) }
        if (d.ownStale) append(", ").append(w.noRecentFix)
        else if (d.stale) append(", ").append(w.outOfDate)
    }

    /** What to tell the rider about the GPS itself, or null when it needs no comment. */
    fun gpsNote(
        state: LocationState,
        haveDistance: Boolean,
        ownStale: Boolean,
        w: DistanceWords = EnglishDistanceWords,
    ): String? = when (state) {
        LocationState.Off -> null
        LocationState.Searching -> w.searchingGps
        LocationState.NoPermission -> w.noPermission
        LocationState.Disabled -> w.locationOff
        LocationState.Unavailable -> w.noGps
        LocationState.Active ->
            if (ownStale) w.weakGps
            else if (!haveDistance) w.waitingForOther
            else null
    }
}
