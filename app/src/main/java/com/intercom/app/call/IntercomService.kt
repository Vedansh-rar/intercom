package com.intercom.app.call

import com.intercom.app.ui.labelRes
import com.intercom.app.util.Localized
import com.intercom.app.util.AppLanguage
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.intercom.app.R
import com.intercom.app.RiderIntercomApp
import com.intercom.app.discovery.HostBeacon
import com.intercom.app.floor.FloorState
import com.intercom.app.media.ButtonAction
import com.intercom.app.media.ButtonGesture
import com.intercom.app.media.ButtonInput
import com.intercom.app.media.ButtonSession
import com.intercom.app.transport.LinkRole
import com.intercom.app.util.Permissions
import com.intercom.app.util.TalkMode
import com.intercom.app.util.effectiveTalkMode
import com.intercom.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The call lives here, not in the Activity.
 *
 * One critical Android 14 rule shapes the whole design: a foreground service that
 * declares the microphone type cannot be created while the app is in the background, and
 * the system throws a SecurityException if it is tried. So this service is only ever
 * started from the Start button on screen, and it never tries to restart itself from a
 * receiver after a crash - it posts a notification that opens the app instead, because a
 * silent retry is what produces the one-way-audio bug.
 */
class IntercomService : Service() {

    /** Notification text in the language chosen in Settings, whatever language the phone is in. */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String {
        val language = (application as? RiderIntercomApp)?.prefs?.current?.language ?: AppLanguage.System
        return Localized.context(this, language).getString(id, *args)
    }

    companion object {
        private const val TAG = "IntercomService"

        const val ACTION_START = "com.intercom.app.START"
        const val ACTION_STOP = "com.intercom.app.STOP"
        const val ACTION_TOGGLE_PTT = "com.intercom.app.TOGGLE_PTT"
        const val ACTION_TOGGLE_MUTE = "com.intercom.app.TOGGLE_MUTE"
        const val ACTION_CYCLE_NS = "com.intercom.app.CYCLE_NS"
        const val ACTION_TOGGLE_DEAFEN = "com.intercom.app.TOGGLE_DEAFEN"

        const val EXTRA_GROUP = "group"
        const val EXTRA_PRIORITY = "priority"
        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"

        const val ROLE_HOST = "host"
        const val ROLE_CLIENT = "client"

        private const val NOTIFICATION_ID = 42
        private const val SOS_NOTIFICATION_ID = 44

        /** Live handle for the UI. Null when no call is running. */
        private val _instance = MutableStateFlow<IntercomService?>(null)
        val instance: StateFlow<IntercomService?> = _instance

        fun startCall(context: Context, role: LinkRole, device: BluetoothDevice?) {
            val i = Intent(context, IntercomService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROLE, if (role == LinkRole.Host) ROLE_HOST else ROLE_CLIENT)
                putExtra(EXTRA_DEVICE, device)
            }
            // Must be called while the app is visible; see the class comment.
            context.startForegroundService(i)
        }

        /** Starts a group ride. [priority] is 1 for the host and 0 for riders who join. */
        fun startGroupCall(context: Context, priority: Int) {
            val i = Intent(context, IntercomService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GROUP, true)
                putExtra(EXTRA_PRIORITY, priority)
            }
            context.startForegroundService(i)
        }

        fun stopCall(context: Context) {
            context.startService(
                Intent(context, IntercomService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var controller: CallController
        private set

    private var buttons: ButtonSession? = null
    private var beacon: HostBeacon? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** True while the foreground service is declared with the location type. */
    private var locationType = false

    private val _lastKeyCode = MutableStateFlow(0)
    /** Raw headset key codes, for the calibration screen in settings. */
    val lastKeyCode: StateFlow<Int> = _lastKeyCode

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as RiderIntercomApp
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bm.adapter

        controller = CallController(applicationContext, app.prefs, adapter)
        _instance.value = this

        // "Share my location" switched on or off mid-call: the location foreground type
        // follows it, so the GPS keeps working with the screen off and only then.
        scope.launch {
            app.prefs.settings.map { it.shareLocation }.distinctUntilChanged().collect {
                refreshForegroundTypes()
            }
        }

        // Volume keys as push-to-talk is opt-in and changes what the volume buttons do, so the
        // session is only held (and the keys only taken) while the rider has it switched on.
        scope.launch {
            app.prefs.settings.map { it.volumeKeys }.distinctUntilChanged().collect { on ->
                buttons?.volumeKeys = on
                buttons?.holdSession = on
                if (on) buttons?.claim()
            }
        }

        scope.launch {
            controller.status.collect { s ->
                updateNotification(s)
                handleSosNotification(s)
                // The intercom takes the headset button briefly around each floor change,
                // then hands it straight back to the music app.
                if (s.floor != FloorState.Idle) buttons?.claimBriefly()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> { stopCall(); return START_NOT_STICKY }
            ACTION_TOGGLE_PTT -> controller.togglePushToTalk()
            ACTION_CYCLE_NS -> controller.cycleNoiseSuppression()
            ACTION_TOGGLE_MUTE -> controller.toggleMute()
            ACTION_TOGGLE_DEAFEN -> controller.toggleDeafen()
            else -> if (!started) { stopSelf(); return START_NOT_STICKY }
        }
        // Never STICKY: the system would try to recreate a microphone-type service from
        // the background, which Android 14 refuses.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (started) return

        val roleName = intent.getStringExtra(EXTRA_ROLE) ?: ROLE_HOST
        val role = if (roleName == ROLE_HOST) LinkRole.Host else LinkRole.Client
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = intent.getParcelableExtra(EXTRA_DEVICE)

        goForeground(controller.status.value)
        acquireWakeLock()

        val app = application as RiderIntercomApp
        if (app.prefs.current.headsetButtonEnabled) {
            buttons = ButtonSession(
                context = this,
                onGesture = ::onButtonGesture,
                onRawKey = { code -> _lastKeyCode.value = code },
                onVolumeKey = { up ->
                    val input = if (up) ButtonInput.VolumeUp else ButtonInput.VolumeDown
                    handleAction(app.prefs.current.buttons.actionFor(input))
                },
            ).also {
                it.volumeKeys = app.prefs.current.volumeKeys
                it.holdSession = app.prefs.current.volumeKeys
                it.claim()
            }
        }

        val group = intent.getBooleanExtra(EXTRA_GROUP, false)

        // While hosting, tell nearby phones so a buddy can join by tapping a name
        // instead of hunting through the paired-devices list. A group ride has no buddy
        // list: riders find each other through Nearby Connections.
        if (role == LinkRole.Host && !group) {
            val bm2 = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            beacon = HostBeacon(bm2.adapter).also {
                it.start(app.prefs.current.riderName, acceptingJoiners = true)
            }
        }

        started = true
        if (group) controller.startGroup(intent.getIntExtra(EXTRA_PRIORITY, 0))
        else controller.start(role, device)
    }

    /**
     * The button means different things depending on whether a call is up, because the
     * music app owns it the rest of the time.
     */
    private fun onButtonGesture(g: ButtonGesture) {
        Log.i(TAG, "headset gesture $g")
        val app = application as RiderIntercomApp
        handleAction(app.prefs.current.buttons.forGesture(g))
    }

    /** A button, remote or notification asked for [action]. */
    private fun handleAction(action: ButtonAction) {
        Log.i(TAG, "button action $action")
        if (action == ButtonAction.EndCall) stopCall() else controller.perform(action)
    }

    // ----------------------------------------------------------------- SOS alert

    private var lastSosAt: Long? = null

    /**
     * A received SOS has to reach a rider whose phone is in a pocket or locked. The call screen
     * shows the banner when the app is in front; this makes the phone light up and show it
     * when it is not, and takes the notification away again once it is dismissed.
     */
    private fun handleSosNotification(s: CallStatus) {
        val sos = s.sosIn
        val nm = getSystemService(NotificationManager::class.java)
        if (sos == null) {
            if (lastSosAt != null) {
                lastSosAt = null
                runCatching { nm.cancel(SOS_NOTIFICATION_ID) }
            }
            return
        }
        if (lastSosAt == sos.atMs) return
        lastSosAt = sos.atMs
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, RiderIntercomApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(str(R.string.svc_sos_from, sos.from))
            .setContentText(str(R.string.svc_sos_open))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // No coordinates here: a notification can be read on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(SOS_NOTIFICATION_ID, n) }
    }

    // ------------------------------------------------------------- foreground

    /** Location is part of the service only while the rider shares it and has granted it. */
    private fun locationInUse(): Boolean =
        (application as RiderIntercomApp).prefs.current.shareLocation && Permissions.hasLocation(this)

    private fun foregroundTypes(withLocation: Boolean): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        // Android 14 throws if the location type is declared without the permission, so it
        // is added only when the permission is in hand, never "just in case".
        if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    private fun tryStartForeground(notification: Notification, types: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed (types=$types)", t)
        false
    }

    private fun goForeground(status: CallStatus) {
        val notification = buildNotification(status)
        val wantLocation = locationInUse()
        if (tryStartForeground(notification, foregroundTypes(wantLocation))) {
            locationType = wantLocation
            return
        }
        // Location is optional: if only it was the problem, the call must not be lost.
        if (wantLocation && tryStartForeground(notification, foregroundTypes(false))) {
            locationType = false
            return
        }
        // On Android 14+ this is the SecurityException from trying to create a
        // microphone-type service from the background. Tell the rider rather than
        // silently retrying into one-way audio.
        notifyServiceFailed()
        stopSelf()
    }

    /**
     * Brings the declared types in line with the rider's setting while a call is running.
     * Unlike [goForeground] a failure here changes nothing and never ends the call: the call
     * keeps its microphone service and location simply stays as it was.
     */
    private fun refreshForegroundTypes() {
        if (!started) return
        val want = locationInUse()
        if (want == locationType) return
        if (tryStartForeground(buildNotification(controller.status.value), foregroundTypes(want))) {
            locationType = want
        }
    }

    private fun updateNotification(status: CallStatus) {
        if (!started) return
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(status)) }
    }

    private fun buildNotification(status: CallStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun action(label: String, act: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(
                this, code,
                Intent(this, IntercomService::class.java).apply { action = act },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        val floorText = if (status.deafened) str(R.string.svc_deafened)
        else if (status.micMuted) str(R.string.svc_muted)
        else when (status.floor) {
            FloorState.Talking -> str(R.string.svc_you_talking)
            FloorState.Muted -> str(R.string.svc_peer_talking, status.peerName ?: str(R.string.call_buddy))
            FloorState.Idle -> status.statusText
        }

        val app = application as RiderIntercomApp
        val settings = app.prefs.current

        val builder = NotificationCompat.Builder(this, RiderIntercomApp.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (status.isConnected) str(R.string.svc_intercom_with, status.peerName ?: str(R.string.call_buddy_lower))
                else if (status.isHost) str(R.string.svc_hosting_waiting)
                else str(R.string.app_name_header)
            )
            .setContentText(floorText)
            .setSubText(
                str(
                    R.string.svc_subtext,
                    str(settings.effectiveTalkMode.labelRes()),
                    str(settings.noiseSuppression.labelRes()),
                )
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Android shows three actions, so they are chosen: Stop is always last; Mute is always
        // there and shows the real state; the middle slot is Talk in push-to-talk (the one
        // control that mode needs) and Deafen otherwise. Noise cycling stays on the call screen
        // and can be given to a headset button.
        builder.addAction(
            action(if (status.micMuted) str(R.string.group_unmute) else str(R.string.group_mute), ACTION_TOGGLE_MUTE, 5)
        )
        if (settings.effectiveTalkMode == TalkMode.PushToTalk) {
            builder.addAction(
                action(if (status.pushToTalk) str(R.string.svc_act_talking) else str(R.string.svc_act_talk), ACTION_TOGGLE_PTT, 1)
            )
        } else {
            builder.addAction(action(if (status.deafened) str(R.string.svc_act_undeafen) else str(R.string.svc_act_deafen), ACTION_TOGGLE_DEAFEN, 6))
        }
        // Stop is always last and always present: it is the one the rider reaches for
        // with a glove on, and it must never move around between states.
        builder.addAction(action(str(R.string.hfp_stop), ACTION_STOP, 2))
        return builder.build()
    }

    private fun notifyServiceFailed() {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, RiderIntercomApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(str(R.string.svc_stopped))
            .setContentText(str(R.string.svc_stopped_detail))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(43, n) }
    }

    // ------------------------------------------------------------- wake locks

    /** The radio and the audio threads need the CPU awake for the whole ride. */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RiderIntercom::call",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- teardown

    private fun stopCall() {
        if (!started) { stopSelf(); return }
        started = false
        controller.stop()
        beacon?.stop()
        beacon = null
        buttons?.release()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        buttons?.destroy()
        buttons = null
        beacon?.stop()
        beacon = null
        runCatching { controller.destroy() }
        releaseWakeLock()
        _instance.value = null
        scope.cancel()
        super.onDestroy()
    }
}
