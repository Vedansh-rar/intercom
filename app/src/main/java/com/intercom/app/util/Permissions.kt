package com.intercom.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions the call needs, in the two stages the design calls for:
 * Bluetooth, microphone and notifications at first launch; location only where an older
 * Android still demands it for Bluetooth work.
 */
object Permissions {

    /** Everything needed before the Start button can do anything. */
    fun required(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
            // Needed so other riders can find this phone when it is hosting.
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            // On Android 10-11 Bluetooth work is gated behind location instead.
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
            // Wi-Fi Direct needs this from Android 13. Declaring it in the manifest is
            // not enough: without the runtime grant createGroup() just fails, which is
            // what produced "failed to create a wifi group" on the first road test.
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.toTypedArray()
    }

    /**
     * Extra for a group ride. Nearby Connections scans and advertises on Bluetooth and
     * Wi-Fi, and on Android 12 and 12L (API 31-32) it still wants fine location for that.
     * Older versions already ask for it in [required]; 13+ uses NEARBY_WIFI_DEVICES, also
     * already asked for. Location is only for the radio scan: nothing reads the position.
     */
    fun groupExtras(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT <= 32) {
            // Android 12+ ignores a request for FINE on its own: COARSE must come with it.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        } else emptyArray()

    fun missingForGroup(context: Context): List<String> =
        (required().toList() + groupExtras()).distinct().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasGroup(context: Context): Boolean = missingForGroup(context).isEmpty()

    fun missing(context: Context): List<String> =
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * Re-checked before every socket call, because a permission revoked while the app ran
     * turns into a SecurityException from connect() or accept() rather than a prompt.
     */
    fun hasBluetooth(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    /** Wi-Fi Direct is gated behind this on 13+, and behind location before that. */
    fun hasNearbyWifi(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * What "Share my location with riders" needs: precise location, requested together with
     * approximate (Android 12+ requires the pair in one request; the rider may then grant
     * only approximate, which is not enough for a distance between two riders and is treated
     * as "not granted" by [hasLocation]).
     */
    fun locationPermissions(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Precise location is granted. Re-checked whenever it is used: it can be revoked at any time. */
    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun label(permission: String): String = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect"
        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertise (so buddies can find you)"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (needed for Bluetooth on this Android)"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location (requested together with precise)"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi (for the long-range link)"
        else -> permission.substringAfterLast('.')
    }
}
