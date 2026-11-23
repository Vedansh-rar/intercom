package com.intercom.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intercom.app.R
import com.intercom.app.audio.MicSource
import com.intercom.app.discovery.NearbyHost
import com.intercom.app.audio.NoiseSuppression
import com.intercom.app.util.LastRole
import com.intercom.app.util.OemDetector
import com.intercom.app.util.Settings as RiderSettings
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

/**
 * The screen the ride starts from.
 *
 * One rider hosts and waits; the other picks them from the paired list and joins. The app
 * never triggers pairing itself - it lists what Android has already bonded, because a
 * pairing prompt in the middle of a connect is one of the failure modes this design sets
 * out to avoid.
 */
@SuppressLint("MissingPermission")
@Composable
fun StartScreen(
    settings: RiderSettings,
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    bondedDevices: List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRenameRider: (String) -> Unit,
    onRideCodeChange: (String) -> Unit,
    onHost: () -> Unit,
    onJoin: (BluetoothDevice) -> Unit,
    onSettings: () -> Unit,
    onOem: () -> Unit,
    onStartGroup: (priority: Int) -> Unit,
    onGroupRide: (Boolean) -> Unit,
) {
    var hostModeState by remember { mutableStateOf(true) }
    // The whole screen recolours with the choice: orange hosting, blue joining.
    WithAccents(host = hostModeState) {
        StartScreenContent(
            settings, permissionsGranted, bluetoothOn, bondedDevices, nearbyHosts,
            onRequestPermissions, onEnableBluetooth, onOpenBluetoothSettings,
            onRenameRider, onRideCodeChange, onHost, onJoin, onSettings, onOem,
            hostMode = hostModeState,
            onHostMode = { hostModeState = it },
            onStartGroup = onStartGroup,
            onGroupRide = onGroupRide,
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun StartScreenContent(
    settings: RiderSettings,
    permissionsGranted: Boolean,
    bluetoothOn: Boolean,
    bondedDevices: List<BluetoothDevice>,
    nearbyHosts: List<NearbyHost>,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRenameRider: (String) -> Unit,
    onRideCodeChange: (String) -> Unit,
    onHost: () -> Unit,
    onJoin: (BluetoothDevice) -> Unit,
    onSettings: () -> Unit,
    onOem: () -> Unit,
    hostMode: Boolean,
    onHostMode: (Boolean) -> Unit,
    onStartGroup: (Int) -> Unit,
    onGroupRide: (Boolean) -> Unit,
) {
    var selected by remember(bondedDevices) {
        mutableStateOf(
            bondedDevices.firstOrNull { it.address == settings.buddyAddress }
                ?: bondedDevices.firstOrNull()
        )
    }
    var showBuddies by remember { mutableStateOf(false) }
    var name by remember(settings.riderName) { mutableStateOf(settings.riderName) }

    val ready = permissionsGranted && bluetoothOn
    val musicIcon = ImageVector.vectorResource(R.drawable.ic_music_note)
    val audioIcon = ImageVector.vectorResource(R.drawable.ic_volume_up)
    // Both phones must share a ride code, unless the rider has chosen to allow unprotected calls.
    // A group ride is always encrypted, so it has no "unencrypted buddy" way round the code.
    val group = settings.groupRide
    val hasCode = settings.rideCode.isNotBlank() || (!group && settings.allowLegacyBuddy)
    val canGo = ready && hasCode && (group || hostMode || selected != null)
    // The host of a group starts it with priority 1; riders who join have 0.
    val go: () -> Unit = {
        if (group) onStartGroup(if (hostMode) 1 else 0)
        else if (hostMode) onHost() else selected?.let(onJoin)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 26.dp)
    ) {
        BrandHeader(
            trailing = {
                Box(
                    Modifier
                        .size(Comfort.MIN_TOUCH_DP.dp)
                        .clip(CircleShape)
                        .background(Brand.Surface)
                        .clickable(role = Role.Button, onClick = onSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.common_settings),
                        tint = Brand.TextPrimary,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        )

        // ---- hero ---------------------------------------------------------------
        Image(
            painter = painterResource(R.drawable.hero_riders),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(176.dp),
        )

        Spacer(Modifier.height(6.dp))

        // ---- someone is hosting nearby ------------------------------------------
        val joinable = nearbyHosts.filter { it.acceptingJoiners }
        if (joinable.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                BrandCard(Modifier.fillMaxWidth(), selected = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WaveMark(Modifier.size(22.dp, 20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (joinable.size == 1) stringResource(R.string.start_one_hosting)
                            else stringResource(R.string.start_many_hosting, joinable.size),
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalAccents.current.base,
                        )
                    }
                    joinable.forEach { host ->
                        val bonded = bondedDevices.firstOrNull { it.address == host.address }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    host.riderName,
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    color = Brand.TextPrimary,
                                )
                                Text(
                                    if (bonded != null) host.proximity
                                    else stringResource(R.string.start_host_needs_pairing, host.proximity),
                                    fontSize = 12.sp, color = Brand.TextDim,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            if (bonded != null) {
                                Box(Modifier.width(96.dp)) {
                                    PrimaryButton(stringResource(R.string.common_join), true, { onJoin(bonded) }, height = 42.dp)
                                }
                            } else {
                                Box(Modifier.width(96.dp)) {
                                    SecondaryButton(stringResource(R.string.start_pair), onOpenBluetoothSettings, height = 42.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- the two ways to start ----------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionTile(
                icon = Icons.Filled.PlayArrow,
                title = stringResource(R.string.start_host_ride),
                subtitle = if (group) stringResource(R.string.start_host_sub_group) else stringResource(R.string.start_host_sub_buddy),
                selected = hostMode,
                enabled = ready,
                onClick = { onHostMode(true); showBuddies = false },
                modifier = Modifier.weight(1f),
            )
            ActionTile(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.start_join_rider),
                subtitle = if (group) stringResource(R.string.start_join_sub_group) else stringResource(R.string.start_join_sub_buddy),
                selected = !hostMode,
                enabled = ready,
                onClick = { onHostMode(false); showBuddies = !group },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- state --------------------------------------------------------------
        val (headline, detail, dot) = when {
            !permissionsGranted -> Triple(
                stringResource(R.string.start_perm_needed), stringResource(R.string.start_perm_needed_detail), Brand.Danger,
            )
            !bluetoothOn -> Triple(stringResource(R.string.start_bt_off), stringResource(R.string.start_bt_off_detail), Brand.Danger)
            !hasCode -> Triple(stringResource(R.string.start_set_code), stringResource(R.string.start_set_code_detail), Brand.Buddy)
            group && hostMode -> Triple(stringResource(R.string.start_ready_lead), stringResource(R.string.start_ready_lead_detail), Brand.Talking)
            group -> Triple(stringResource(R.string.start_ready_join_group), stringResource(R.string.start_ready_join_group_detail), Brand.Talking)
            hostMode -> Triple(stringResource(R.string.start_ready_host), stringResource(R.string.start_ready_host_detail), Brand.Talking)
            selected != null -> Triple(
                stringResource(R.string.start_ready_join),
                runCatching { selected?.name }.getOrNull() ?: selected?.address ?: "",
                Brand.Talking,
            )
            else -> Triple(stringResource(R.string.start_no_buddy), stringResource(R.string.start_no_buddy_detail), Brand.Buddy)
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusPill(headline = headline, detail = detail, dotColor = dot)
        }

        // ---- fix-it cards, only when something is actually wrong ------------------
        if (!permissionsGranted) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.start_perm_explain),
                        fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                    )
                    PrimaryButton(stringResource(R.string.start_grant_permissions), true, onRequestPermissions)
                }
            }
        } else if (!bluetoothOn) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.start_bt_on_explain),
                        fontSize = 14.sp, color = Brand.TextDim,
                    )
                    PrimaryButton(stringResource(R.string.start_turn_on_bt), true, onEnableBluetooth)
                }
            }
        }

        // ---- reconnect to the last buddy ----------------------------------------
        val lastBuddy = settings.buddyName?.takeIf { it.isNotBlank() }
        val lastDevice = bondedDevices.firstOrNull { it.address == settings.buddyAddress }
        val canReconnect = ready && hasCode && !group && when (settings.lastRole) {
            LastRole.Host -> true
            LastRole.Client -> lastDevice != null
            LastRole.None -> false
        }
        if (settings.lastRole != LastRole.None && !group && lastBuddy != null) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.start_last_ride),
                        fontSize = 11.sp, letterSpacing = 1.6.sp,
                        fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                    )
                    Text(
                        if (settings.lastRole == LastRole.Host) stringResource(R.string.start_last_hosted, lastBuddy)
                        else stringResource(R.string.start_last_joined, lastBuddy),
                        fontSize = 15.sp, color = Brand.TextPrimary,
                    )
                    PrimaryButton(
                        text = stringResource(R.string.start_reconnect, lastBuddy),
                        enabled = canReconnect,
                        onClick = {
                            if (settings.lastRole == LastRole.Host) onHost()
                            else lastDevice?.let(onJoin)
                        },
                    )
                }
            }
        }

        // ---- the dial -----------------------------------------------------------
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(enabled = canGo) { go() },
                ) {
                    MicDial(
                        color = if (canGo) LocalAccents.current.base else Brand.Idle,
                        icon = MicIcons.Mic,
                        active = canGo,
                    )
                }
                Spacer(Modifier.height(14.dp))
                DotStatus(
                    text = if (canGo) stringResource(R.string.start_ready) else stringResource(R.string.start_not_ready),
                    color = if (canGo) Brand.Talking else Brand.Idle,
                    subtitle = if (canGo) stringResource(R.string.start_ready_hint) else detail,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- start button -------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            PrimaryButton(
                text = if (group) { if (hostMode) stringResource(R.string.start_group_start) else stringResource(R.string.start_group_join) }
                else if (hostMode) stringResource(R.string.start_start_hosting) else {
                    val label = selected?.let { d ->
                        runCatching { d.name }.getOrNull() ?: d.address
                    }
                    if (label != null) stringResource(R.string.start_join_with, label) else stringResource(R.string.common_join)
                },
                enabled = canGo,
                onClick = go,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- buddy picker (two-rider calls only) --------------------------------
        if (!group) Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Comfort.MIN_TOUCH_DP.dp).clickable { showBuddies = !showBuddies },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.start_paired_phones),
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                        )
                        Text(
                            if (bondedDevices.isEmpty()) stringResource(R.string.start_none_yet)
                            else stringResource(R.string.start_n_available, bondedDevices.size),
                            fontSize = 15.sp, color = Brand.TextPrimary,
                        )
                    }
                    Text(
                        if (showBuddies) stringResource(R.string.common_hide) else stringResource(R.string.common_show),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = LocalAccents.current.base,
                    )
                }

                if (showBuddies) {
                    if (bondedDevices.isEmpty()) {
                        Text(
                            if (!permissionsGranted) stringResource(R.string.start_grant_bt_to_list)
                            else stringResource(R.string.start_pair_first),
                            fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                        )
                    } else {
                        bondedDevices.forEach { device ->
                            val isSel = selected?.address == device.address
                            val label = runCatching { device.name }.getOrNull() ?: device.address
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSel) Brand.SurfaceSelected else Color.Transparent)
                                    .clickable {
                                        selected = device
                                        onHostMode(false)
                                    }
                                    .heightIn(min = Comfort.MIN_TOUCH_DP.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        label, fontSize = 16.sp,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = Brand.TextPrimary,
                                    )
                                    Text(device.address, fontSize = 12.sp, color = Brand.TextDim)
                                }
                                if (isSel) {
                                    Icon(
                                        Icons.Filled.Check, contentDescription = stringResource(R.string.start_selected),
                                        tint = LocalAccents.current.base, modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    SecondaryButton(
                        text = if (bondedDevices.isEmpty()) stringResource(R.string.start_open_bt_settings)
                        else stringResource(R.string.start_pair_another),
                        onClick = onOpenBluetoothSettings,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- your name ----------------------------------------------------------
        Box(Modifier.padding(horizontal = 16.dp)) {
            BrandCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.start_your_name),
                    fontSize = 11.sp, letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(14)
                        onRenameRider(name)
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.start_name_hint), color = Brand.TextDim) },
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Brand.SurfaceElevated,
                        unfocusedContainerColor = Brand.SurfaceElevated,
                        focusedIndicatorColor = LocalAccents.current.base,
                        unfocusedIndicatorColor = Brand.Border,
                        focusedTextColor = Brand.TextPrimary,
                        unfocusedTextColor = Brand.TextPrimary,
                        cursorColor = LocalAccents.current.base,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---- group ride opt-in --------------------------------------------------
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            GroupRideCard(enabled = group, onChange = onGroupRide, internet = settings.internetRide)
        }

        // ---- the ride code ------------------------------------------------------
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            RideCodeCard(code = settings.rideCode, onChange = onRideCodeChange)
        }

        // ---- what the three subsystems will do -----------------------------------
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 16.dp)) {
            StatusBar(
                listOf(
                    BarItem(
                        icon = musicIcon,
                        title = stringResource(R.string.common_music),
                        subtitle = if (settings.takeAudioFocus) stringResource(R.string.start_music_ducks)
                        else stringResource(R.string.common_keeps_playing),
                        onClick = onSettings,
                    ),
                    BarItem(
                        icon = MicIcons.Mic,
                        title = stringResource(R.string.common_intercom),
                        subtitle = stringResource(settings.talkMode.labelRes()),
                        active = ready,
                        onClick = {},
                    ),
                    BarItem(
                        icon = audioIcon,
                        title = stringResource(R.string.common_noise),
                        subtitle = stringResource(settings.noiseSuppression.labelRes()),
                        active = settings.noiseSuppression != NoiseSuppression.Off,
                        onClick = onSettings,
                    ),
                )
            )
        }

        // ---- OEM warning --------------------------------------------------------
        if (OemDetector.isAggressiveVendor()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = 16.dp)) {
                BrandCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock, contentDescription = null,
                            tint = Brand.Buddy, modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.start_before_long_ride),
                            fontSize = 11.sp, letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
                        )
                    }
                    Text(
                        stringResource(R.string.start_oem_warning),
                        fontSize = 14.sp, color = Brand.TextDim, lineHeight = 19.sp,
                    )
                    SecondaryButton(stringResource(R.string.start_keep_alive), onOem, color = Brand.Buddy)
                }
            }
        }
    }
}
