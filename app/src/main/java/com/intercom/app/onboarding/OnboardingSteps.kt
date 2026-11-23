package com.intercom.app.onboarding

import android.Manifest
import android.os.Build

/** The pages of the first-run walkthrough, in the order a rider meets them. */
enum class OnboardingStep {
    Welcome, Microphone, Bluetooth, Notifications, Location, Battery, TestCall, Done
}

/**
 * Which pages a rider sees, and which permission each one asks for. Pure functions of the
 * Android version and the rider's settings, so the order and the "location only if it is
 * switched on" rule can be tested without a phone.
 */
object OnboardingPlan {

    /**
     * Location is part of the walkthrough only when the rider has already switched on
     * "Share my location" (it is off by default and asked for there, at that moment). The
     * battery page is shown on every phone: the exemption matters everywhere, the vendor steps
     * only on the phones that need them.
     */
    fun steps(shareLocation: Boolean, notificationsAreRuntime: Boolean = Build.VERSION.SDK_INT >= 33): List<OnboardingStep> =
        buildList {
            add(OnboardingStep.Welcome)
            add(OnboardingStep.Microphone)
            add(OnboardingStep.Bluetooth)
            if (notificationsAreRuntime) add(OnboardingStep.Notifications)
            if (shareLocation) add(OnboardingStep.Location)
            add(OnboardingStep.Battery)
            add(OnboardingStep.TestCall)
            add(OnboardingStep.Done)
        }

    /** The runtime permissions a step asks for. Empty for pages that only explain or test. */
    fun permissionsFor(step: OnboardingStep, sdk: Int = Build.VERSION.SDK_INT): List<String> = when (step) {
        OnboardingStep.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
        OnboardingStep.Bluetooth -> buildList {
            if (sdk >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                // Android 10 and 11 gate Bluetooth work behind location.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        OnboardingStep.Notifications ->
            if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        OnboardingStep.Location ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> emptyList()
    }

    fun next(steps: List<OnboardingStep>, current: OnboardingStep): OnboardingStep {
        val i = steps.indexOf(current)
        return if (i < 0 || i >= steps.lastIndex) steps.last() else steps[i + 1]
    }

    fun previous(steps: List<OnboardingStep>, current: OnboardingStep): OnboardingStep {
        val i = steps.indexOf(current)
        return if (i <= 0) steps.first() else steps[i - 1]
    }

    /** 1-based position for "2 of 7"; the welcome and done pages are not counted as steps of their own. */
    fun progress(steps: List<OnboardingStep>, current: OnboardingStep): Pair<Int, Int> =
        (steps.indexOf(current).coerceAtLeast(0) + 1) to steps.size
}
