package com.intercom.app

import com.intercom.app.update.UpdateController
import com.intercom.app.call.IntercomService
import com.intercom.app.call.CallPhase
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.intercom.app.util.Localized
import com.intercom.app.util.Prefs

class RiderIntercomApp : Application() {

    lateinit var prefs: Prefs
        private set

    /** The opt-in update check; idle and silent until the rider asks. */
    val updates: UpdateController by lazy {
        UpdateController(this, prefs, callActive = {
            val phase = IntercomService.instance.value?.controller?.status?.value?.phase
            phase != null && phase !in listOf(CallPhase.Idle, CallPhase.Ended, CallPhase.Error)
        })
    }

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
