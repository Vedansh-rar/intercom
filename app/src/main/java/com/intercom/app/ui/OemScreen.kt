package com.intercom.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.intercom.app.R
import com.intercom.app.util.OemDetector

/**
 * The screen that decides whether the call survives a ride.
 *
 * Standard Android keeps a foreground service alive; several manufacturers do not, and
 * no amount of correct code changes that. The only fix is walking the rider through that
 * vendor's own settings once.
 */
@Composable
fun OemScreen(
    onBack: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.oem_title),
            subtitle = stringResource(R.string.oem_subtitle),
            onBack = onBack,
        )
        OemGuideBody(onOpenAppSettings, onRequestBatteryExemption, onOpenOemSettings)
    }
}

/** The step cards, shared by this screen and the first-run walkthrough. */
@Composable
fun OemGuideBody(
    onOpenAppSettings: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenOemSettings: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val guidance = OemDetector.guidanceFor()
    val exempt = OemDetector.isIgnoringBatteryOptimisations(context)

    Column {
        SectionCard(title = stringResource(R.string.oem_step1_title)) {
            Text(
                if (exempt) stringResource(R.string.oem_step1_done)
                else stringResource(R.string.oem_step1_ask),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!exempt) {
                PrimaryButton(stringResource(R.string.oem_allow_background), true, onRequestBatteryExemption)
            }
        }

        if (guidance != null) {
            SectionCard(title = stringResource(R.string.oem_step2_vendor, guidance.vendor)) {
                Text(
                    stringResource(R.string.oem_vendor_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IntercomColors.dim,
                )
                guidance.steps.forEachIndexed { i, step ->
                    Text(
                        stringResource(R.string.oem_step_line, i + 1, stringResource(step)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (OemDetector.canOpen(context, guidance.intent)) {
                    PrimaryButton(
                        text = stringResource(R.string.oem_open_vendor, guidance.vendor),
                        enabled = true,
                        onClick = { guidance.intent?.let(onOpenOemSettings) },
                    )
                }
            }
        } else {
            SectionCard(title = stringResource(R.string.oem_step2_generic)) {
                Text(
                    stringResource(R.string.oem_no_vendor),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionCard(title = stringResource(R.string.oem_expect_title)) {
            Text(
                stringResource(R.string.oem_expect_body),
                style = MaterialTheme.typography.bodyMedium,
                color = IntercomColors.dim,
            )
            SecondaryButton(stringResource(R.string.oem_open_app_settings), onOpenAppSettings)
        }
    }
}
