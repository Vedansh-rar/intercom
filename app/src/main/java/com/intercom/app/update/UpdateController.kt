package com.intercom.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.intercom.app.util.Prefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the in-app update is. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val offer: UpdateOffer) : UpdateState
    data class Downloading(val offer: UpdateOffer, val bytes: Long) : UpdateState
    /** Verified and ready for the system installer. */
    data class Ready(val offer: UpdateOffer) : UpdateState
    data class Failed(val error: UpdateError) : UpdateState
}

/** What the activity should launch to install. */
sealed interface InstallAction {
    /** The system's "install unknown apps" switch for this app is off; open it. */
    data class NeedsPermission(val intent: Intent) : InstallAction
    data class Install(val intent: Intent) : InstallAction
}

/**
 * Checks GitHub Releases, downloads and verifies an APK, and hands it to the system
 * installer. Every step is started by the rider (or by the opt-in daily check, which only
 * checks); nothing downloads or installs by itself.
 *
 * The call must not be running: installing replaces the app and ends the call, so the
 * download and the install are refused while one is active.
 */
class UpdateController(
    private val context: Context,
    private val prefs: Prefs,
    private val callActive: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var connection: java.net.HttpURLConnection? = null
    @Volatile private var readyFile: File? = null

    private val userAgent: String by lazy { "RiderIntercom/${versionName()} (update check)" }

    fun isCallActive(): Boolean = callActive()

    fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    private fun updatesDir(): File = File(context.cacheDir, DIR).apply { mkdirs() }

    private fun clean() {
        updatesDir().listFiles()?.forEach { it.delete() }
        readyFile = null
    }

    /** Asks GitHub for the latest release. Does nothing if a check or download is already running. */
    fun check() {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        prefs.lastUpdateCheckMs = clock()
        job = scope.launch {
            try {
                clean()
                _state.value = runCheck()
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(e.error)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = if (isActive) UpdateState.Failed(UpdateError.Offline) else UpdateState.Idle
            }
        }
    }

    private fun runCheck(): UpdateState {
        val current = SemVer.parse(versionName()) ?: return UpdateState.Failed(UpdateError.BadTag)
        val body = UpdateHttp.getText(API_URL, userAgent, "application/vnd.github+json", MAX_JSON_BYTES)
        val release = ReleaseParser.parse(body) ?: throw UpdateException(UpdateError.BadResponse)

        // Only a newer release needs its checksum fetched.
        var checksumText: String? = null
        if (release.version.isNewerThan(current)) {
            val apk = ReleaseParser.pickApk(release.assets)
            val sumAsset = apk?.let { ReleaseParser.checksumAsset(release.assets, it) }
            if (sumAsset != null && UpdateHosts.isAllowed(sumAsset.url)) {
                checksumText = UpdateHttp.getText(sumAsset.url, userAgent, "text/plain, application/octet-stream", MAX_CHECKSUM_BYTES)
            }
        }
        return when (val d = ReleaseParser.decide(release, current, checksumText)) {
            is ReleaseCheck.Offer -> UpdateState.Available(d.offer)
            is ReleaseCheck.UpToDate -> UpdateState.UpToDate(current.toString())
            is ReleaseCheck.Problem -> UpdateState.Failed(UpdateError.of(d.problem))
        }
    }

    /** Downloads the offered APK, verifying size and SHA-256 as it streams to disk. */
    fun download() {
        val offer = (_state.value as? UpdateState.Available)?.offer ?: return
        if (job?.isActive == true) return
        if (callActive()) {
            _state.value = UpdateState.Failed(UpdateError.CallActive)
            return
        }
        _state.value = UpdateState.Downloading(offer, 0)
        job = scope.launch {
            val part = File(updatesDir(), "update.part")
            try {
                clean()
                val file = fetch(offer, part) { !isActive }
                _state.value = UpdateState.Ready(offer)
                readyFile = file
            } catch (e: UpdateException) {
                part.delete()
                _state.value = if (e.error == UpdateError.Cancelled) UpdateState.Available(offer) else UpdateState.Failed(e.error)
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: java.io.IOException) {
                part.delete()
                // Cancel closes the socket under the reader, which surfaces as an IOException.
                _state.value = if (!isActive) UpdateState.Available(offer) else UpdateState.Failed(
                    if (e.message?.contains("ENOSPC") == true || e is java.io.FileNotFoundException) UpdateError.Storage else UpdateError.Offline,
                )
            } catch (e: Exception) {
                part.delete()
                _state.value = UpdateState.Failed(UpdateError.BadResponse)
            }
        }
    }

    private fun fetch(offer: UpdateOffer, part: File, cancelled: () -> Boolean): File {
        val conn = UpdateHttp.open(offer.apkUrl, userAgent, "application/octet-stream")
        connection = conn
        try {
            val declared = conn.contentLengthLong
            if (declared > 0 && declared != offer.apkSize) throw UpdateException(UpdateError.SizeMismatch)
            var lastBytes = 0L
            val result = conn.inputStream.use { input ->
                part.outputStream().buffered().use { out ->
                    ApkStream.copy(input, out, offer.apkSize, isCancelled = cancelled) { n ->
                        // Update the screen about 100 times, not once per 64 KB.
                        if (n - lastBytes >= offer.apkSize / 100 + 1) {
                            lastBytes = n
                            _state.value = UpdateState.Downloading(offer, n)
                        }
                    }
                }
            }
            if (!ApkStream.sameDigest(result.sha256, offer.sha256)) {
                part.delete()
                throw UpdateException(UpdateError.ChecksumMismatch)
            }
            // Only a verified file gets an .apk name.
            val apk = File(updatesDir(), "rider-intercom-${offer.version}.apk")
            if (!part.renameTo(apk)) throw UpdateException(UpdateError.Storage)
            ApkInspector.inspect(context, apk)?.let {
                apk.delete()
                throw UpdateException(it)
            }
            return apk
        } finally {
            connection = null
            conn.disconnect()
        }
    }

    /** Stops a running check or download. A cancelled download leaves nothing on disk. */
    fun cancel() {
        job?.cancel()
        connection?.disconnect()
    }

    /** Back to the idle state, deleting any downloaded file. */
    fun dismiss() {
        cancel()
        job = null
        clean()
        _state.value = UpdateState.Idle
    }

    /** The intent that installs the verified file, or null when nothing is ready or a call is running. */
    fun installAction(): InstallAction? {
        val file = readyFile ?: return null
        if (_state.value !is UpdateState.Ready || !file.isFile) return null
        if (callActive()) {
            _state.value = UpdateState.Failed(UpdateError.CallActive)
            return null
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return InstallAction.NeedsPermission(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        return InstallAction.Install(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        /** The repository whose releases are checked. */
        const val REPO = "Vedansh-rar/intercom"
        const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val DIR = "updates"
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_CHECKSUM_BYTES = 4 * 1024
    }
}
