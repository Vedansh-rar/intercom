package com.intercom.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import com.intercom.app.R

/**
 * Several manufacturers kill long-running foreground services whatever the standard
 * Android rules say, so the rider has to be walked through that OEM's own settings once.
 * This is the difference between a call that survives a ride and one that dies ten
 * minutes after the screen goes off.
 */
object OemDetector {

    data class Guidance(
        val vendor: String,
        /** String resources; shown with `stringResource` / `context.getString`. */
        @StringRes val steps: List<Int>,
        /** Vendor settings screen to open directly, when one is known. */
        val intent: Intent?,
    )

    private val manufacturer: String get() = Build.MANUFACTURER.lowercase()

    fun isAggressiveVendor(): Boolean = guidanceFor() != null

    fun guidanceFor(): Guidance? = guidanceFor(manufacturer)

    /** Split out so the vendor matching can be unit-tested without a device. Expects a lower-case name. */
    fun guidanceFor(manufacturer: String): Guidance? = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
            manufacturer.contains("poco") -> Guidance(
            vendor = "Xiaomi / Redmi / POCO",
            steps = listOf(
                R.string.oem_mi_1,
                R.string.oem_mi_2,
                R.string.oem_mi_3,
                R.string.oem_mi_4,
                R.string.oem_mi_5,
            ),
            intent = tryComponent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        )
        manufacturer.contains("oppo") || manufacturer.contains("realme") -> Guidance(
            vendor = "OPPO / realme",
            steps = listOf(
                R.string.oem_oppo_1,
                R.string.oem_oppo_2,
                R.string.oem_oppo_3,
            ),
            intent = tryComponent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
        )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Guidance(
            vendor = "vivo / iQOO",
            steps = listOf(
                R.string.oem_vivo_1,
                R.string.oem_vivo_2,
                R.string.oem_vivo_3,
            ),
            intent = tryComponent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        )
        manufacturer.contains("oneplus") -> Guidance(
            vendor = "OnePlus",
            steps = listOf(
                R.string.oem_oneplus_1,
                R.string.oem_oneplus_2,
                R.string.oem_oneplus_3,
            ),
            intent = tryComponent(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        )
        manufacturer.contains("samsung") -> Guidance(
            vendor = "Samsung",
            steps = listOf(
                R.string.oem_samsung_1,
                R.string.oem_samsung_2,
                R.string.oem_samsung_3,
            ),
            intent = tryComponent(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> Guidance(
            vendor = "Huawei / Honor",
            steps = listOf(
                R.string.oem_huawei_1,
                R.string.oem_huawei_2,
            ),
            intent = tryComponent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
        else -> null
    }

    private fun tryComponent(pkg: String, cls: String): Intent = Intent().apply {
        component = ComponentName(pkg, cls)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun canOpen(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun isIgnoringBatteryOptimisations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @Suppress("BatteryLife")
    fun batteryOptimisationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun bluetoothSettingsIntent(): Intent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
