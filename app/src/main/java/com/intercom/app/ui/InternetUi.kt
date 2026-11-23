package com.intercom.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.intercom.app.R
import com.intercom.app.relay.RelayUrl
import com.intercom.app.util.Settings as RiderSettings

/** The body of the Settings "Internet ride" card. */
@Composable
fun InternetRideSection(settings: RiderSettings, onChange: ((RiderSettings) -> RiderSettings) -> Unit) {
    val dim = IntercomColors.dim
    Text(stringResource(R.string.inet_sub), style = MaterialTheme.typography.bodyMedium, color = dim)
    ToggleRow(
        checked = settings.internetRide,
        title = stringResource(R.string.inet_toggle),
        subtitle = stringResource(R.string.inet_toggle_sub),
        onChange = { v -> onChange { it.copy(internetRide = v) } },
    )
    var text by remember { mutableStateOf(settings.relayUrl) }
    val bad = text.isNotBlank() && RelayUrl.parse(text) == null
    Text(stringResource(R.string.inet_relay_label), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(200)
            // Stored as typed; it is validated and normalised when a ride starts.
            onChange { it.copy(relayUrl = text.trim()) }
        },
        singleLine = true,
        isError = bad,
        placeholder = { Text(stringResource(R.string.inet_relay_hint), color = Brand.TextDim) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
    if (bad) Text(stringResource(R.string.inet_relay_bad), style = MaterialTheme.typography.bodyMedium, color = Brand.Danger)
    Text(stringResource(R.string.inet_privacy), style = MaterialTheme.typography.bodyMedium, color = dim)
    Text(stringResource(R.string.inet_data), style = MaterialTheme.typography.bodyMedium, color = dim)
    Text(stringResource(R.string.inet_no_position), style = MaterialTheme.typography.bodyMedium, color = dim)
}
