package com.intercom.app.ui

import androidx.annotation.StringRes
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.intercom.app.R
import com.intercom.app.update.UpdateError
import com.intercom.app.update.UpdateState
import java.util.Locale

/** What the Updates section can ask for. Each one is a deliberate tap by the rider. */
class UpdateActions(
    val check: () -> Unit = {},
    val download: () -> Unit = {},
    val cancel: () -> Unit = {},
    val install: () -> Unit = {},
    val close: () -> Unit = {},
)

@StringRes
fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.Offline -> R.string.upd_err_offline
    UpdateError.RateLimited -> R.string.upd_err_rate_limited
    UpdateError.NoRelease -> R.string.upd_err_no_release
    UpdateError.ServerError -> R.string.upd_err_server
    UpdateError.BadResponse -> R.string.upd_err_bad_response
    UpdateError.BadTag -> R.string.upd_err_bad_tag
    UpdateError.NoApk -> R.string.upd_err_no_apk
    UpdateError.NoChecksum -> R.string.upd_err_no_checksum
    UpdateError.BadHost -> R.string.upd_err_bad_host
    UpdateError.BadSize -> R.string.upd_err_bad_size
    UpdateError.ChecksumMismatch -> R.string.upd_err_checksum
    UpdateError.SizeMismatch -> R.string.upd_err_size
    UpdateError.Storage -> R.string.upd_err_storage
    UpdateError.WrongPackage -> R.string.upd_err_wrong_package
    UpdateError.WrongSignature -> R.string.upd_err_wrong_signature
    UpdateError.NotNewer -> R.string.upd_err_not_newer
    UpdateError.Unreadable -> R.string.upd_err_unreadable
    UpdateError.CallActive -> R.string.upd_err_call
    UpdateError.Cancelled -> R.string.upd_err_cancelled
}

private fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

/** The body of the Settings "Updates" card. */
@Composable
fun UpdateSection(
    state: UpdateState,
    appVersion: String,
    autoCheck: Boolean,
    callActive: Boolean,
    actions: UpdateActions,
    onAutoCheck: (Boolean) -> Unit,
) {
    val dim = IntercomColors.dim
    Text(stringResource(R.string.upd_sub), style = MaterialTheme.typography.bodyMedium, color = dim)
    Text(stringResource(R.string.upd_version, appVersion), style = MaterialTheme.typography.bodyLarge)

    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> Text(stringResource(R.string.upd_checking), style = MaterialTheme.typography.bodyLarge)
        is UpdateState.UpToDate -> Text(
            stringResource(R.string.upd_up_to_date, state.version),
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.Talking,
        )
        is UpdateState.Available -> {
            Text(
                stringResource(
                    R.string.upd_available, state.offer.version.toString(),
                    stringResource(R.string.upd_size_mb, megabytes(state.offer.apkSize)),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.offer.notes.isNotBlank()) {
                Text(state.offer.notes.take(400), style = MaterialTheme.typography.bodyMedium, color = dim)
            }
        }
        is UpdateState.Downloading -> {
            Text(
                stringResource(
                    R.string.upd_downloading,
                    stringResource(R.string.upd_size_mb, megabytes(state.bytes)),
                    stringResource(R.string.upd_size_mb, megabytes(state.offer.apkSize)),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { (state.bytes.toFloat() / state.offer.apkSize.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = Brand.Accent,
                trackColor = Brand.SurfaceElevated,
            )
        }
        is UpdateState.Ready -> {
            Text(
                stringResource(R.string.upd_ready, state.offer.version.toString()),
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.Talking,
            )
        }
        is UpdateState.Failed -> Text(
            stringResource(state.error.messageRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.Danger,
        )
    }

    if (callActive) {
        Text(stringResource(R.string.upd_call_active), style = MaterialTheme.typography.bodyMedium, color = dim)
    }

    when (state) {
        UpdateState.Checking -> SecondaryButton(stringResource(R.string.upd_cancel), actions.cancel)
        is UpdateState.Downloading -> SecondaryButton(stringResource(R.string.upd_cancel), actions.cancel)
        is UpdateState.Available -> {
            PrimaryButton(stringResource(R.string.upd_download), !callActive, actions.download)
            SecondaryButton(stringResource(R.string.upd_close), actions.close)
        }
        is UpdateState.Ready -> {
            PrimaryButton(stringResource(R.string.upd_install), !callActive, actions.install)
            Text(stringResource(R.string.upd_checksum_note), style = MaterialTheme.typography.bodyMedium, color = dim)
            SecondaryButton(stringResource(R.string.upd_close), actions.close)
        }
        else -> SecondaryButton(
            text = stringResource(R.string.upd_check),
            onClick = actions.check,
            enabled = !callActive,
        )
    }

    ToggleRow(
        checked = autoCheck,
        title = stringResource(R.string.upd_auto),
        subtitle = stringResource(R.string.upd_auto_sub),
        onChange = onAutoCheck,
    )
}
