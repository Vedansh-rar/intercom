package com.intercom.app.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.intercom.app.BuildConfig
import com.intercom.app.util.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Share log": reads this app's own log lines, strips the personal parts, writes a text file
 * in the app's private cache and hands it to the Android share sheet. Nothing is uploaded by
 * the app; where it goes next is the rider's choice in the share sheet.
 */
object LogExporter {

    private const val DIR = "logs"
    private const val KEEP = 3

    /** Builds the file. Call off the main thread: it runs `logcat` and waits for it. */
    fun export(context: Context, settings: Settings, now: Long = System.currentTimeMillis()): File {
        val raw = readOwnLog()
        val redactor = LogRedactor(
            names = listOfNotNull(settings.riderName, settings.buddyName),
            secrets = listOf(settings.rideCode),
        )
        val facts = LogReport.Facts(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            generatedAtMs = now,
            settings = listOf(
                "Transport" to settings.transport.name,
                "Talk mode" to settings.talkMode.name,
                "Mic" to settings.micSource.name,
                "Noise" to "${settings.noiseSuppression.name}/${settings.noiseEngine.name}",
                "Group ride" to settings.groupRide.toString(),
                "Location sharing" to settings.shareLocation.toString(),
            ),
        )
        val text = LogReport.build(facts, raw, redactor)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        prune(dir)
        return File(dir, "rider-intercom-log-$now.txt").apply { writeText(text) }
    }

    fun shareIntent(context: Context, file: File, chooserTitle: String, subject: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Without the READ_LOGS permission Android hands an app only its own lines, which is all this wants. */
    private fun readOwnLog(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "8000")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroy()
        out
    } catch (t: Throwable) {
        "(the log could not be read: ${t.javaClass.simpleName})"
    }

    private fun prune(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP - 1)?.forEach { it.delete() }
    }
}
