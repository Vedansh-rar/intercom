package com.intercom.app.ui

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.intercom.app.audio.PlatformAudioEffects
import com.intercom.app.call.CallStatus
import com.intercom.app.util.effectiveTalkMode
import com.intercom.app.discovery.NearbyHost
import com.intercom.app.call.IntercomService
import com.intercom.app.transport.LinkRole
import com.intercom.app.util.Permissions
import com.intercom.app.util.Prefs
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import com.intercom.app.summary.RideSummaryStore
import com.intercom.app.selftest.SelfTest
import com.intercom.app.util.AppLanguage
import com.intercom.app.call.CallPhase
import com.intercom.app.R

@Composable
fun AppRoot(
    prefs: Prefs,
    screen: Screen,
    onScreen: (Screen) -> Unit,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    bluetoothOn: Boolean,
    onEnableBluetooth: () -> Unit,
    bondedDevices: () -> List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onOpenBluetoothSettings: () -> Unit,
    onStartCall: (LinkRole, BluetoothDevice?) -> Unit,
    onStartGroup: (priority: Int) -> Unit,
    onStopCall: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
    onRequestLocation: () -> Unit,
    locationDenied: Boolean,
    permissionVersion: Int = 0,
    selfTest: SelfTest,
    onAskPermissions: (List<String>) -> Unit = {},
    onFinishOnboarding: () -> Unit = {},
    onLanguage: (AppLanguage) -> Unit = {},
    onShareLog: () -> Unit = {},
    shareLogBusy: Boolean = false,
    updateState: com.intercom.app.update.UpdateState = com.intercom.app.update.UpdateState.Idle,
    appVersion: String = "",
    updateActions: UpdateActions = UpdateActions(),
) {
    val settings by prefs.settings.collectAsState()
    val ctx = LocalContext.current
    // Checked here rather than mid-ride, so the rider learns before they set off.
    val wifiDirectProblemRes = remember(settings.transport) { wifiDirectBlocker(ctx) }
    val wifiDirectProblem = wifiDirectProblemRes?.let { stringResource(it) }
    val service by IntercomService.instance.collectAsState()
    val status: CallStatus = service?.controller?.status?.collectAsState()?.value ?: CallStatus()

    // A call that just ended leaves a summary behind; show it from the screens a rider ends a call on.
    val summary by RideSummaryStore.latest.collectAsState()
    LaunchedEffect(summary, screen) {
        if (summary != null && (screen == Screen.Call || screen == Screen.Start)) onScreen(Screen.Summary)
    }
    // The system Back button follows the on-screen one on the screens that have no other way out.
    BackHandler(enabled = screen == Screen.Summary) {
        RideSummaryStore.clear()
        onScreen(Screen.Start)
    }
    BackHandler(enabled = screen == Screen.Onboarding) { onFinishOnboarding() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Android 15+ forces edge-to-edge and ignores setDecorFitsSystemWindows, so the
        // insets are applied here or the header draws under the status bar.
        Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (screen) {
                Screen.Start -> StartScreen(
                    settings = settings,
                    permissionsGranted = permissionsGranted,
                    bluetoothOn = bluetoothOn,
                    bondedDevices = remember(permissionsGranted, bluetoothOn) { bondedDevices() },
                    nearbyHosts = nearbyHosts,
                    onRequestPermissions = onRequestPermissions,
                    onEnableBluetooth = onEnableBluetooth,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                    onRenameRider = { name -> prefs.update { it.copy(riderName = name) } },
                    onRideCodeChange = { code -> prefs.update { it.copy(rideCode = code) } },
                    onHost = { onStartCall(LinkRole.Host, null) },
                    onJoin = { device -> onStartCall(LinkRole.Client, device) },
                    onSettings = { onScreen(Screen.Settings) },
                    onOem = { onScreen(Screen.Oem) },
                    onStartGroup = onStartGroup,
                    onGroupRide = { on -> prefs.update { it.copy(groupRide = on) } },
                    onWalkie = { on -> prefs.update { it.copy(walkieMode = on) } },
                )

                Screen.Call -> CallScreen(
                    status = status,
                    riderName = settings.riderName,
                    micLevel = service?.controller?.micLevel?.collectAsState()?.value ?: 0f,
                    talkMode = settings.effectiveTalkMode,
                    noiseSuppression = settings.noiseSuppression,
                    onCycleNoise = { service?.controller?.cycleNoiseSuppression() },
                    onPushToTalkDown = { service?.controller?.setPushToTalk(true) },
                    onPushToTalkUp = { service?.controller?.setPushToTalk(false) },
                    onEnd = {
                        onStopCall()
                        onScreen(Screen.Start)
                    },
                    onBack = { onScreen(Screen.Start) },
                    onSettings = { onScreen(Screen.Settings) },
                    onPeerMute = { id, muted -> service?.controller?.setPeerMuted(id, muted) },
                    distanceUnit = settings.distanceUnit,
                    onToggleMute = { service?.controller?.toggleMute() },
                    onToggleDeafen = { service?.controller?.toggleDeafen() },
                    onSendSos = { service?.controller?.triggerSos() },
                    onDismissSosOut = { service?.controller?.dismissSosOut() },
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    lastKeyCode = service?.lastKeyCode?.collectAsState()?.value ?: 0,
                    platformEffects = remember { PlatformAudioEffects().availability() },
                    wifiDirectReady = wifiDirectProblem == null,
                    wifiDirectProblem = wifiDirectProblem ?: "",
                    onChange = { transform ->
                        prefs.update(transform)
                        service?.controller?.applySettingsChange()
                    },
                    onBack = {
                        onScreen(if (status.isConnected) Screen.Call else Screen.Start)
                    },
                    onOem = { onScreen(Screen.Oem) },
                    onHfp = { onScreen(Screen.Hfp) },
                    // Location is asked for only here, at the moment the rider switches the
                    // feature on, never at launch.
                    onShareLocation = { on ->
                        if (on && !Permissions.hasLocation(ctx)) {
                            onRequestLocation()
                        } else {
                            prefs.update { it.copy(shareLocation = on) }
                            service?.controller?.applySettingsChange()
                        }
                    },
                    locationDenied = locationDenied,
                    onOpenAppSettings = onOpenAppSettings,
                    onLanguage = onLanguage,
                    onShareLog = onShareLog,
                    shareLogBusy = shareLogBusy,
                    onRunSetup = { onScreen(Screen.Onboarding) },
                    updateState = updateState,
                    appVersion = appVersion,
                    callActive = status.phase !in listOf(
                        com.intercom.app.call.CallPhase.Idle,
                        com.intercom.app.call.CallPhase.Ended,
                        com.intercom.app.call.CallPhase.Error,
                    ),
                    updateActions = updateActions,
                )

                Screen.Oem -> OemScreen(
                    onBack = { onScreen(Screen.Start) },
                    onOpenAppSettings = onOpenAppSettings,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenOemSettings = onOpenOemSettings,
                )

                Screen.Hfp -> HfpScreen(
                    onBack = { onScreen(Screen.Settings) },
                )

                Screen.Onboarding -> OnboardingScreen(
                    shareLocation = settings.shareLocation,
                    permissionVersion = permissionVersion,
                    bluetoothOn = bluetoothOn,
                    callActive = status.phase !in listOf(CallPhase.Idle, CallPhase.Ended, CallPhase.Error),
                    selfTest = selfTest,
                    onRequestPermissions = onAskPermissions,
                    onEnableBluetooth = onEnableBluetooth,
                    onOpenAppSettings = onOpenAppSettings,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onOpenOemSettings = onOpenOemSettings,
                    onFinish = onFinishOnboarding,
                )

                Screen.Summary -> {
                    val done = summary
                    if (done == null) {
                        LaunchedEffect(Unit) { onScreen(Screen.Start) }
                    } else {
                        SummaryScreen(done, onDone = {
                            RideSummaryStore.clear()
                            onScreen(Screen.Start)
                        })
                    }
                }
            }
        }

        // A received SOS covers everything, on whatever screen the rider is on.
        val sosIn = status.sosIn
        ShowOverLockScreen(sosIn != null)
        if (sosIn != null) {
            SosBanner(sosIn, settings.distanceUnit, onDismiss = { service?.controller?.dismissSosIn() })
        }
        }
    }
}

/** Lets the activity appear over the lock screen and wake the display while an SOS is up. */
@Composable
private fun ShowOverLockScreen(on: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(on) {
        val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        if (on && activity != null) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        onDispose {
            if (on && activity != null) {
                activity.setShowWhenLocked(false)
                activity.setTurnScreenOn(false)
            }
        }
    }
}

/**
 * Why Wi-Fi Direct cannot be used on this phone right now, or null when it can.
 * Mirrors the preconditions `WifiDirectLink` checks, so settings and the live link agree.
 */
@androidx.annotation.StringRes
private fun wifiDirectBlocker(context: android.content.Context): Int? {
    val p2p = context.getSystemService(android.content.Context.WIFI_P2P_SERVICE)
    if (p2p == null) return R.string.wifi_blocker_none
    if (!com.intercom.app.util.Permissions.hasNearbyWifi(context)) {
        return R.string.wifi_blocker_permission
    }
    val wm = context.applicationContext
        .getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    if (wm != null && !wm.isWifiEnabled) return R.string.wifi_blocker_off
    return null
}
