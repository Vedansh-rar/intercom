package com.intercom.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.intercom.app.security.RideCode
import com.intercom.app.R
import androidx.compose.ui.res.stringResource

/**
 * The shared secret for the ride.
 *
 * Both phones need the same code. The host reads it out or shows the QR; the joiner types
 * it or scans it. It is never transmitted: it only ever appears on a screen, and the
 * handshake proves each side knows it without sending it.
 */
@Composable
fun RideCodeCard(
    code: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(code) { RideCode.parseOrNull(code) }
    val context = LocalContext.current

    // First launch: hand the rider a good code rather than an empty box.
    LaunchedEffect(Unit) {
        if (parsed == null) onChange(RideCode.generate().canonical)
    }

    var showQr by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var typedError by remember { mutableStateOf<RideCode.Parsed.Invalid?>(null) }
    val notARideCode = stringResource(R.string.code_qr_not_code)
    val qrUnavailable = stringResource(R.string.code_qr_unavailable)
    val codeDescription = stringResource(R.string.code_description, parsed?.display ?: stringResource(R.string.code_not_set))

    BrandCard(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.code_title),
            fontSize = 11.sp, letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold, color = Brand.TextDim,
        )
        Text(
            parsed?.display ?: "-",
            fontSize = 21.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = Brand.TextPrimary,
            modifier = Modifier.semantics { contentDescription = codeDescription },
        )
        Text(
            stringResource(R.string.code_explain),
            fontSize = 13.sp, color = Brand.TextDim, lineHeight = 18.sp,
        )
        if (parsed != null && parsed.isQuickCode) {
            Text(
                stringResource(R.string.code_quick_warning),
                fontSize = 13.sp, color = Brand.Buddy, lineHeight = 18.sp,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                stringResource(R.string.code_new_words),
                { onChange(RideCode.generate().canonical); showQr = false },
                modifier = Modifier.weight(1f), height = 46.dp,
            )
            SecondaryButton(
                stringResource(R.string.code_new_number),
                { onChange(RideCode.generateDigits().canonical); showQr = false },
                modifier = Modifier.weight(1f), height = 46.dp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                if (showQr) stringResource(R.string.code_hide_qr) else stringResource(R.string.code_show_qr),
                { showQr = !showQr },
                modifier = Modifier.weight(1f), height = 46.dp,
                enabled = parsed != null,
            )
            SecondaryButton(
                stringResource(R.string.code_scan_qr),
                {
                    scanQr(context, qrUnavailable) { raw ->
                        val scanned = raw?.let { RideCode.fromQrPayload(it) }
                        if (scanned != null) {
                            onChange(scanned.canonical)
                            editing = false
                        } else if (raw != null) {
                            toast(context, notARideCode)
                        }
                    }
                },
                modifier = Modifier.weight(1f), height = 46.dp,
            )
        }

        if (showQr && parsed != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrImage(RideCode.toQrPayload(parsed))
            }
            Text(
                stringResource(R.string.code_show_only_buddy),
                fontSize = 12.sp, color = Brand.TextDim,
            )
        }

        SecondaryButton(
            if (editing) stringResource(R.string.common_cancel) else stringResource(R.string.code_type),
            { editing = !editing; typed = ""; typedError = null },
            height = 46.dp, color = Brand.TextDim,
        )
        if (editing) {
            val errorText = typedError?.let { rideCodeProblemText(it) }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it; typedError = null },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.code_example), color = Brand.TextDim) },
                isError = typedError != null,
                supportingText = if (errorText != null) {
                    { Text(errorText, color = Brand.Danger) }
                } else null,
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
            PrimaryButton(
                stringResource(R.string.code_use),
                enabled = typed.isNotBlank(),
                onClick = {
                    when (val r = RideCode.parse(typed)) {
                        is RideCode.Parsed.Valid -> {
                            onChange(r.code.canonical)
                            editing = false
                            typed = ""
                        }
                        is RideCode.Parsed.Invalid -> typedError = r
                    }
                },
                height = 48.dp,
            )
        }
    }
}

/** The QR for [payload], drawn black on white with a quiet zone so any camera reads it. */
@Composable
private fun QrImage(payload: String) {
    val matrix = remember(payload) {
        runCatching {
            QRCodeWriter().encode(
                payload, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.MARGIN to 2,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                ),
            )
        }.getOrNull()
    }
    val qrDescription = stringResource(R.string.code_qr_description)
    Canvas(
        Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .semantics { contentDescription = qrDescription },
    ) {
        val m = matrix ?: return@Canvas
        val cell = size.width / m.width
        for (x in 0 until m.width) {
            for (y in 0 until m.height) {
                if (m.get(x, y)) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        // A hair of overlap so no seams show between cells.
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * Opens Google's code scanner. It draws its own camera screen inside Play services, so the
 * app needs no camera permission. Without Play services the call fails and the rider is
 * told to type the code instead.
 */
private fun scanQr(context: Context, unavailableMessage: String, onResult: (String?) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    runCatching {
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { onResult(it.rawValue ?: "") }
            .addOnCanceledListener { /* the rider backed out */ }
            .addOnFailureListener { toast(context, unavailableMessage) }
    }.onFailure { toast(context, unavailableMessage) }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** What went wrong with a typed code, in the rider's language. */
@Composable
private fun rideCodeProblemText(p: RideCode.Parsed.Invalid): String = when (p.problem) {
    RideCode.Problem.Empty -> stringResource(R.string.code_err_empty)
    RideCode.Problem.NumberLength -> stringResource(R.string.code_err_digits, RideCode.DIGITS_LENGTH)
    RideCode.Problem.WordsOnly -> stringResource(R.string.code_err_words_only, RideCode.DIGITS_LENGTH)
    RideCode.Problem.TooFewWords -> stringResource(R.string.code_err_too_few, RideCode.MIN_WORDS)
    RideCode.Problem.TooManyWords -> stringResource(R.string.code_err_too_many, RideCode.MAX_WORDS)
    RideCode.Problem.UnknownWord -> stringResource(R.string.code_err_unknown_word, p.detail ?: "")
    RideCode.Problem.Other -> p.reason
}
