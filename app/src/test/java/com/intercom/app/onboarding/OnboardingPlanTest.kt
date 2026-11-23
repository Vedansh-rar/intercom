package com.intercom.app.onboarding

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.intercom.app.onboarding.OnboardingStep as S

class OnboardingPlanTest {

    @Test fun locationIsOnlyAskedWhenSharingIsOn() {
        assertFalse(S.Location in OnboardingPlan.steps(shareLocation = false, notificationsAreRuntime = true))
        val on = OnboardingPlan.steps(shareLocation = true, notificationsAreRuntime = true)
        assertTrue(S.Location in on)
        assertTrue(on.indexOf(S.Location) > on.indexOf(S.Notifications))
        assertTrue(on.indexOf(S.Location) < on.indexOf(S.Battery))
    }

    @Test fun notificationsPageOnlyWhereTheyAreARuntimePermission() {
        assertFalse(S.Notifications in OnboardingPlan.steps(false, notificationsAreRuntime = false))
        assertTrue(S.Notifications in OnboardingPlan.steps(false, notificationsAreRuntime = true))
    }

    @Test fun theOrderIsWelcomePermissionsBatteryTestDone() {
        assertEquals(
            listOf(S.Welcome, S.Microphone, S.Bluetooth, S.Notifications, S.Battery, S.TestCall, S.Done),
            OnboardingPlan.steps(false, true),
        )
    }

    @Test fun bluetoothPermissionsFollowTheAndroidVersion() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), OnboardingPlan.permissionsFor(S.Bluetooth, 30))
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE),
            OnboardingPlan.permissionsFor(S.Bluetooth, 31),
        )
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in OnboardingPlan.permissionsFor(S.Bluetooth, 33))
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in OnboardingPlan.permissionsFor(S.Bluetooth, 32))
    }

    @Test fun notificationsPermissionOnlyFrom33() {
        assertTrue(OnboardingPlan.permissionsFor(S.Notifications, 32).isEmpty())
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), OnboardingPlan.permissionsFor(S.Notifications, 34))
    }

    @Test fun locationAsksPreciseAndApproximateTogether() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            OnboardingPlan.permissionsFor(S.Location, 34),
        )
    }

    @Test fun explainerPagesAskForNothing() {
        for (s in listOf(S.Welcome, S.Battery, S.TestCall, S.Done)) {
            assertTrue(OnboardingPlan.permissionsFor(s, 34).isEmpty())
        }
    }

    @Test fun nextAndPreviousStopAtTheEnds() {
        val steps = OnboardingPlan.steps(false, true)
        assertEquals(S.Microphone, OnboardingPlan.next(steps, S.Welcome))
        assertEquals(S.Done, OnboardingPlan.next(steps, S.Done))
        assertEquals(S.Welcome, OnboardingPlan.previous(steps, S.Welcome))
        assertEquals(S.TestCall, OnboardingPlan.previous(steps, S.Done))
    }

    @Test fun aStepNotInThePlanIsTreatedAsTheStart() {
        val steps = OnboardingPlan.steps(false, true)
        assertEquals(S.Done, OnboardingPlan.next(steps, S.Location))
        assertEquals(S.Welcome, OnboardingPlan.previous(steps, S.Location))
    }

    @Test fun progressIsOneBased() {
        val steps = OnboardingPlan.steps(false, true)
        assertEquals(1 to steps.size, OnboardingPlan.progress(steps, S.Welcome))
        assertEquals(steps.size to steps.size, OnboardingPlan.progress(steps, S.Done))
    }
}
