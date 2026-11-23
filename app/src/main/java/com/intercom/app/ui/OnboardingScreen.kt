package com.intercom.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.intercom.app.R
import com.intercom.app.onboarding.OnboardingPlan
import com.intercom.app.onboarding.OnboardingStep
import com.intercom.app.selftest.RouteKind
import com.intercom.app.selftest.RouteReport
import com.intercom.app.selftest.SelfTest
import com.intercom.app.selftest.SelfTestState
import com.intercom.app.util.Permissions

/**
 * First-run walkthrough: permissions one at a time with the reason for each, the battery
 * settings that decide whether a call survives a ride, and a test call through the headset.
 *
 * Every page can be skipped and the whole thing can be run again from Settings. Skipping is
 * safe: the start screen still explains whatever is missing when the rider tries to ride.
 */
@Composable
fun OnboardingScreen(
    shareLocation: Boolean,
    /** Bumped by the activity whenever a permission result or a resume might have changed a grant. */
    permissionVersion: Int,
    bluetoothOn: Boolean,
    callActive: Boolean,
    selfTest: SelfTest,
    onRequestPermissions: (List<String>) -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
    onFinish: () -> Unit,
) {
    val ctx = LocalContext.current
    val steps = remember(shareLocation) { OnboardingPlan.steps(shareLocation) }
    var index by rememberSaveable { mutableStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val (n, total) = OnboardingPlan.progress(steps, step)
    val last = step == steps.last()

    fun go(to: OnboardingStep) { index = steps.indexOf(to).coerceAtLeast(0) }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ob_progress, n, total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
                LinearProgressIndicator(
                    progress = { n.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = Brand.Accent,
                    trackColor = Brand.SurfaceElevated,
                )
            }
            if (!last) {
                Box(
                    Modifier.padding(start = 12.dp).heightIn(min = Comfort.MIN_TOUCH_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.ob_skip), color = Brand.TextDim)
                    }
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            when (step) {
                OnboardingStep.Welcome -> InfoPage(R.string.ob_welcome_title, R.string.ob_welcome_body, R.string.ob_welcome_privacy)

                OnboardingStep.Microphone -> PermissionPage(
                    step, permissionVersion, R.string.ob_mic_title, R.string.ob_mic_body, R.string.ob_mic_button,
                    onRequestPermissions, onOpenAppSettings,
                )

                OnboardingStep.Bluetooth -> PermissionPage(
                    step, permissionVersion, R.string.ob_bt_title, R.string.ob_bt_body, R.string.ob_bt_button,
                    onRequestPermissions, onOpenAppSettings,
                ) {
                    if (!bluetoothOn && Permissions.hasBluetooth(ctx)) {
                        Text(
                            stringResource(R.string.ob_bt_is_off),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Brand.Buddy,
                        )
                        SecondaryButton(stringResource(R.string.ob_bt_turn_on), onEnableBluetooth)
                    }
                }

                OnboardingStep.Notifications -> PermissionPage(
                    step, permissionVersion, R.string.ob_notif_title, R.string.ob_notif_body, R.string.ob_notif_button,
                    onRequestPermissions, onOpenAppSettings,
                )

                OnboardingStep.Location -> PermissionPage(
                    step, permissionVersion, R.string.ob_loc_title, R.string.ob_loc_body, R.string.ob_loc_button,
                    onRequestPermissions, onOpenAppSettings,
                )

                OnboardingStep.Battery -> {
                    PageTitle(R.string.ob_battery_title, R.string.ob_battery_body)
                    // Re-read the exemption when the rider comes back from Android's dialog.
                    key(permissionVersion) {
                        OemGuideBody(onOpenAppSettings, onRequestBatteryExemption, onOpenOemSettings)
                    }
                }

                OnboardingStep.TestCall -> TestCallPage(selfTest, callActive, permissionVersion)

                OnboardingStep.Done -> DonePage(ctx, steps, permissionVersion)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (index > 0 && !last) {
                SecondaryButton(
                    text = stringResource(R.string.ob_back),
                    onClick = { go(OnboardingPlan.previous(steps, step)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.weight(if (index > 0 && !last) 1.4f else 1f)) {
                PrimaryButton(
                    text = stringResource(
                        when {
                            last -> R.string.ob_done_button
                            step == OnboardingStep.Welcome -> R.string.ob_start
                            else -> R.string.ob_next
                        },
                    ),
                    enabled = true,
                    onClick = { if (last) onFinish() else go(OnboardingPlan.next(steps, step)) },
                )
            }
        }
    }
}

@Composable
private fun PageTitle(@StringRes title: Int, @StringRes body: Int) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, color = Brand.TextPrimary)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = IntercomColors.dim)
    }
}

@Composable
private fun InfoPage(@StringRes title: Int, @StringRes body: Int, @StringRes extra: Int) {
    PageTitle(title, body)
    SectionCard {
        Text(stringResource(extra), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun granted(context: Context, permissions: List<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** One permission, its reason, a button, and what happened. */
@Composable
private fun PermissionPage(
    step: OnboardingStep,
    permissionVersion: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes button: Int,
    onRequest: (List<String>) -> Unit,
    onOpenAppSettings: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val ctx = LocalContext.current
    val wanted = remember(step) { OnboardingPlan.permissionsFor(step) }
    val ok = remember(permissionVersion, step) { granted(ctx, wanted) }
    var asked by rememberSaveable(step) { mutableStateOf(false) }

    PageTitle(title, body)
    SectionCard {
        val state = stringResource(if (ok) R.string.ob_allowed else R.string.ob_not_allowed)
        KeyValueRow(
            label = stringResource(title),
            value = state,
            valueColor = if (ok) Brand.Talking else Brand.Buddy,
        )
        if (!ok) {
            PrimaryButton(
                text = stringResource(button),
                enabled = true,
                onClick = { asked = true; onRequest(wanted) },
            )
            if (asked) {
                Text(
                    stringResource(R.string.ob_denied_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
                SecondaryButton(stringResource(R.string.ob_open_settings), onOpenAppSettings)
            }
        }
        extra()
    }
}

@StringRes
private fun RouteKind.labelRes(): Int = when (this) {
    RouteKind.BluetoothCall -> R.string.route_bt_call
    RouteKind.BluetoothMusic -> R.string.route_bt_music
    RouteKind.BluetoothLe -> R.string.route_bt_le
    RouteKind.Wired -> R.string.route_wired
    RouteKind.Usb -> R.string.route_usb
    RouteKind.Phone -> R.string.route_phone
    RouteKind.None -> R.string.route_none
}

/** What a rider should do about the route, as one sentence; null when it is fine as it is. */
@StringRes
private fun RouteReport.adviceRes(): Int = when {
    noOutput -> R.string.ob_route_no_output
    headsetMicNotUsed -> R.string.ob_route_mic_unused
    outputIsLoudspeaker -> R.string.ob_route_loudspeaker
    input == RouteKind.Phone && output == RouteKind.Phone -> R.string.ob_route_phone
    else -> R.string.ob_route_ok
}

@Composable
private fun TestCallPage(selfTest: SelfTest, callActive: Boolean, permissionVersion: Int) {
    val ctx = LocalContext.current
    val state by selfTest.state.collectAsState()
    val micOk = remember(permissionVersion) { Permissions.hasMicrophone(ctx) }
    // The route can change while the page is open (a headset connecting), so look again when the state moves.
    val route = remember(permissionVersion, state) { selfTest.route() }

    DisposableEffect(Unit) {
        onDispose { selfTest.stop(); selfTest.reset() }
    }

    PageTitle(R.string.ob_test_title, R.string.ob_test_body)

    SectionCard(title = stringResource(R.string.ob_test_route_title)) {
        Text(stringResource(R.string.ob_test_in, stringResource(route.input.labelRes())), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.ob_test_out, stringResource(route.output.labelRes())), style = MaterialTheme.typography.bodyLarge)
        val advice = route.adviceRes()
        Text(
            stringResource(advice),
            style = MaterialTheme.typography.bodyMedium,
            color = if (advice == R.string.ob_route_ok) Brand.Talking else Brand.Buddy,
        )
    }

    SectionCard {
        val meterDescription = stringResource(R.string.ob_meter_desc)
        when (val s = state) {
            is SelfTestState.Listening -> {
                Text(stringResource(R.string.ob_test_listening, s.secondsLeft), style = MaterialTheme.typography.bodyLarge)
                LevelBar(s.level, Brand.Talking, Modifier.semantics { contentDescription = meterDescription })
            }
            SelfTestState.Playing -> Text(stringResource(R.string.ob_test_playing), style = MaterialTheme.typography.bodyLarge)
            is SelfTestState.Done -> {
                Text(
                    stringResource(if (s.heard) R.string.ob_test_heard else R.string.ob_test_quiet),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (s.heard) Brand.Talking else Brand.Buddy,
                )
                if (s.clipped) {
                    Text(stringResource(R.string.ob_test_clipped), style = MaterialTheme.typography.bodyMedium, color = Brand.Buddy)
                }
            }
            SelfTestState.NoMic -> Text(stringResource(R.string.ob_test_nomic), style = MaterialTheme.typography.bodyLarge, color = Brand.Danger)
            SelfTestState.Idle -> Unit
        }
        val busy = state is SelfTestState.Listening || state is SelfTestState.Playing
        when {
            callActive -> Text(stringResource(R.string.ob_test_blocked_call), style = MaterialTheme.typography.bodyMedium, color = IntercomColors.dim)
            !micOk -> Text(stringResource(R.string.ob_test_blocked_mic), style = MaterialTheme.typography.bodyMedium, color = IntercomColors.dim)
        }
        PrimaryButton(
            text = stringResource(if (state is SelfTestState.Done) R.string.ob_test_again else R.string.ob_test_start),
            enabled = !callActive && micOk && !busy,
            onClick = { selfTest.start() },
        )
    }
}

@Composable
private fun DonePage(ctx: Context, steps: List<OnboardingStep>, permissionVersion: Int) {
    val missing = remember(permissionVersion, steps) {
        steps.flatMap { OnboardingPlan.permissionsFor(it) }.distinct().filterNot { granted(ctx, listOf(it)) }
    }
    PageTitle(R.string.ob_done_title, R.string.ob_done_body)
    if (missing.isNotEmpty()) {
        SectionCard {
            Text(
                stringResource(R.string.ob_done_missing, missing.joinToString(", ") { Permissions.label(ctx, it) }),
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.Buddy,
            )
        }
    }
    Spacer(Modifier.padding(4.dp))
}
