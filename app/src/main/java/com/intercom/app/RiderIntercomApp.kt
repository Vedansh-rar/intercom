package com.intercom.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.intercom.app.util.Localized
import com.intercom.app.util.Prefs

class RiderIntercomApp : Application() {

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
    }

    /**
     * Channel names are stored by the system, so they are re-registered (which updates the
     * name and description) whenever the rider changes the app language.
     */
    fun createChannels() {
        val c = Localized.context(this, prefs.current.language)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                c.getString(R.string.chan_call_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = c.getString(R.string.chan_call_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                c.getString(R.string.chan_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = c.getString(R.string.chan_alert_desc)
            }
        )
    }

    companion object {
        const val CHANNEL_CALL = "call"
        const val CHANNEL_ALERT = "alert"
    }
}
