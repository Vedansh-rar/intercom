package com.intercom.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.intercom.app.R
import com.intercom.app.location.DistanceWords

/** The distance words in the rider's language, read from string resources. */
class ResDistanceWords(private val c: Context) : DistanceWords {
    override val ahead get() = c.getString(R.string.dist_ahead)
    override val behind get() = c.getString(R.string.dist_behind)
    override val toYourLeft get() = c.getString(R.string.dist_left)
    override val toYourRight get() = c.getString(R.string.dist_right)
    override fun toThe(compass: String) = c.getString(R.string.dist_to_the, compass)
    override fun secondsAgo(s: Long) = c.getString(R.string.dist_s_ago, s)
    override fun minutesAgo(m: Long) = c.getString(R.string.dist_min_ago, m)
    override val overAnHourAgo get() = c.getString(R.string.dist_hour_ago)
    override fun lastSeen(ago: String) = c.getString(R.string.dist_last_seen, ago)
    override val rider get() = c.getString(R.string.dist_rider)
    override fun away(distance: String, lessThan: Boolean) =
        c.getString(if (lessThan) R.string.dist_less_than_away else R.string.dist_away, distance)
    override val noRecentFix get() = c.getString(R.string.dist_no_fix)
    override val outOfDate get() = c.getString(R.string.dist_out_of_date)
    override val searchingGps get() = c.getString(R.string.gps_searching)
    override val noPermission get() = c.getString(R.string.gps_no_permission)
    override val locationOff get() = c.getString(R.string.gps_location_off)
    override val noGps get() = c.getString(R.string.gps_none)
    override val weakGps get() = c.getString(R.string.gps_weak)
    override val waitingForOther get() = c.getString(R.string.gps_waiting)
}

@Composable
fun rememberDistanceWords(): DistanceWords {
    val c = LocalContext.current
    return remember(c) { ResDistanceWords(c) }
}
