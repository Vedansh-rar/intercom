package com.intercom.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import com.intercom.app.update.InstallAction
import com.intercom.app.update.UpdatePolicy
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intercom.app.RiderIntercomApp
import com.intercom.app.call.CallPhase
import com.intercom.app.discovery.HostScanner
import com.intercom.app.discovery.NearbyHost
import com.intercom.app.call.IntercomService
import com.intercom.app.transport.LinkRole
import com.intercom.app.util.OemDetector
import com.intercom.app.util.Permissions
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intercom.app.R
import com.intercom.app.diagnostics.LogExporter
import com.intercom.app.selftest.SelfTest
import com.intercom.app.util.AppLanguage
import com.intercom.app.util.Localized
import com.intercom.app.util.Prefs

/** Where in the app the rider is. */
enum class Screen { Start, Call, Settings, Oem, Hfp, Onboarding, Summary }

class MainActivity : ComponentActivity() {

    private companion object {
        const val KEY_SCREEN = "screen"
    }

    private lateinit var prefs: Prefs
    private var adapter: BluetoothAdapter? = null

    private var permissionsGranted by mutableStateOf(false)
    private var screen by mutableStateOf(Screen.Start)

    /** Bumped whenever a grant might have changed, so screens that show grants re-read them. */
    private var permissionVersion by mutableStateOf(0)

    private var sharingLog by mutableStateOf(false)
    private var selfTest: SelfTest? = null

    /** Every string the rider sees comes from resources in the language chosen in Settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localized.context(newBase, Localized.languageOf(newBase)))
    }

    /**
     * Scanning only runs while this screen is in front. A background scan would need its
     * own foreground service and would sit on the radio all ride for a feature the rider
     * only uses at the start of one.
     */
    private var scanner: HostScanner? = null
    private var nearbyHosts by mutableStateOf<List<NearbyHost>>(emptyList())

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it } || Permissions.allGranted(this)
        permissionVersion++
    }

    /** True after the rider was asked for precise location and did not allow it. */
    private var locationDenied by mutableStateOf(false)

    /**
     * Asked only when "Share my location with riders" is switched on. Android 12+ needs the
     * approximate permission requested together with the precise one, and the rider may
     * grant only approximate: that is not enough for a distance, so it counts as a no.
     */
    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = Permissions.hasLocation(this)
        locationDenied = !granted
        if (granted) {
            prefs.update { it.copy(shareLocation = true) }
            IntercomService.instance.value?.controller?.applySettingsChange()
        }
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permissionVersion++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        prefs = (application as RiderIntercomApp).prefs
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        permissionsGranted = Permissions.allGranted(this)
        // Comfort settings are plain snapshot state read by the colours and button sizes.
        Comfort.apply(prefs.current.highContrast, prefs.current.largeButtons)
        lifecycleScope.launch {
            prefs.settings.collect { Comfort.apply(it.highContrast, it.largeButtons) }
        }
        // A rebuild (changing the language, rotating) should land the rider where they were.
        savedInstanceState?.getString(KEY_SCREEN)
            ?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?.let { screen = it }
        // First run opens the walkthrough; every later launch goes straight to the start screen.
        if (savedInstanceState == null && !prefs.onboardingDone) screen = Screen.Onboarding

        // Opt-in only: the daily check runs when the rider switched it on, and only looks.
        val updates = (application as RiderIntercomApp).updates
        if (savedInstanceState == null &&
            UpdatePolicy.shouldAutoCheck(
                prefs.current.autoUpdateCheck, prefs.lastUpdateCheckMs, System.currentTimeMillis(), updates.isCallActive(),
            )
        ) {
            updates.check()
        }

        setContent {
            val updateState by updates.state.collectAsState()
            RiderIntercomTheme {
                AppRoot(
                    prefs = prefs,
                    screen = screen,
                    onScreen = { screen = it },
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { askPermissions.launch(Permissions.required()) },
                    bluetoothOn = adapter?.isEnabled == true,
                    onEnableBluetooth = {
                        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    bondedDevices = { bondedDevices() },
                    nearbyHosts = nearbyHosts,
                    onOpenBluetoothSettings = {
                        runCatching { startActivity(OemDetector.bluetoothSettingsIntent()) }
                    },
                    onStartCall = ::startCall,
                    onStartGroup = ::startGroupCall,
                    onStopCall = { IntercomService.stopCall(this) },
                    onOpenAppSettings = {
                        runCatching { startActivity(OemDetector.appDetailsIntent(this)) }
                    },
                    onRequestBatteryExemption = {
                        runCatching { startActivity(OemDetector.batteryOptimisationIntent(this)) }
                    },
                    onOpenOemSettings = { intent ->
                        runCatching { startActivity(intent) }
                    },
                    onRequestLocation = {
                        locationDenied = false
                        askLocation.launch(Permissions.locationPermissions())
                    },
                    locationDenied = locationDenied,
                    permissionVersion = permissionVersion,
                    selfTest = selfTestInstance(),
                    onAskPermissions = { list -> if (list.isNotEmpty()) askPermissions.launch(list.toTypedArray()) },
                    onFinishOnboarding = {
                        prefs.onboardingDone = true
                        screen = Screen.Start
                    },
                    onLanguage = ::changeLanguage,
                    onShareLog = ::shareLog,
                    shareLogBusy = sharingLog,
                    updateState = updateState,
                    appVersion = updates.versionName(),
                    updateActions = UpdateActions(
                        check = updates::check,
                        download = updates::download,
                        cancel = updates::cancel,
                        install = ::installUpdate,
                        close = updates::dismiss,
                    ),
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    private fun selfTestInstance(): SelfTest =
        selfTest ?: SelfTest(this) { prefs.current }.also { selfTest = it }

    /**
     * Saves the choice, renames the notification channels and rebuilds the activity so
     * every screen is re-read in the new language.
     */
    private fun changeLanguage(language: AppLanguage) {
        if (prefs.current.language == language) return
        prefs.update { it.copy(language = language) }
        (application as RiderIntercomApp).createChannels()
        recreate()
    }

    /** Hands the verified APK to the system installer; Android re-checks the signing key itself. */
    private fun installUpdate() {
        val updates = (application as RiderIntercomApp).updates
        when (val action = updates.installAction()) {
            null -> Unit
            is InstallAction.NeedsPermission -> {
                Toast.makeText(this, R.string.upd_install_permission, Toast.LENGTH_LONG).show()
                runCatching { startActivity(action.intent) }
            }
            is InstallAction.Install -> runCatching { startActivity(action.intent) }
                .onFailure { Toast.makeText(this, R.string.upd_err_unreadable, Toast.LENGTH_LONG).show() }
        }
    }

    /** Builds the redacted log off the main thread, then offers it to whatever app the rider picks. */
    private fun shareLog() {
        if (sharingLog) return
        sharingLog = true
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { LogExporter.export(this@MainActivity, prefs.current) }.getOrNull()
            }
            sharingLog = false
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.set_share_log_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val send = LogExporter.shareIntent(
                this@MainActivity, file,
                getString(R.string.set_share_log_chooser), getString(R.string.set_share_log_subject),
            )
            runCatching { startActivity(send) }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = Permissions.allGranted(this)
        permissionVersion++
        startScanning()
        // A call already running means the rider came back to the app mid-ride.
        if (IntercomService.instance.value != null &&
            IntercomService.instance.value?.controller?.status?.value?.phase !in
            listOf(CallPhase.Idle, CallPhase.Ended, null)
        ) {
            screen = Screen.Call
        }
    }

    override fun onPause() {
        super.onPause()
        scanner?.stop()
        selfTest?.stop()
    }

    private fun startScanning() {
        if (!Permissions.hasBluetooth(this) || adapter?.isEnabled != true) return
        val a = adapter ?: return
        val s = scanner ?: HostScanner(a).also { made ->
            scanner = made
            lifecycleScope.launch {
                made.hosts.collect { nearbyHosts = it }
            }
        }
        s.start()
    }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> {
        if (!Permissions.hasBluetooth(this)) return emptyList()
        val a = adapter ?: return emptyList()
        return runCatching { a.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    /**
     * The microphone foreground service can only be created while the app is visible, so
     * this is the only place a call ever starts.
     */
    private fun startCall(role: LinkRole, device: BluetoothDevice?) {
        if (!Permissions.allGranted(this)) {
            askPermissions.launch(Permissions.required())
            return
        }
        if (role == LinkRole.Client) {
            prefs.update {
                it.copy(
                    buddyAddress = device?.address,
                    buddyName = runCatching { device?.name }.getOrNull(),
                )
            }
        }
        IntercomService.startCall(this, role, device)
        screen = Screen.Call
    }

    /**
     * A group ride. Same rule as [startCall]: only from the visible app. Needs the extra
     * radio permissions a convoy scan wants on Android 12 and 12L, asked for here.
     */
    private fun startGroupCall(priority: Int) {
        if (!Permissions.hasGroup(this)) {
            askPermissions.launch((Permissions.required().toList() + Permissions.groupExtras()).distinct().toTypedArray())
            return
        }
        IntercomService.startGroupCall(this, priority)
        screen = Screen.Call
    }
}
