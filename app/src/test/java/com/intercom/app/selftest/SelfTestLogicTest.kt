package com.intercom.app.selftest

import android.media.AudioDeviceInfo as D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutesTest {
    @Test fun bluetoothMusicOnlyMeansTheHeadsetMicIsNotUsed() {
        val r = AudioRoutes.report(listOf(D.TYPE_BUILTIN_MIC), listOf(D.TYPE_BLUETOOTH_A2DP, D.TYPE_BUILTIN_SPEAKER))
        assertEquals(RouteKind.Phone, r.input)
        assertEquals(RouteKind.BluetoothMusic, r.output)
        assertTrue(r.headsetMicNotUsed)
        assertFalse(r.outputIsLoudspeaker)
    }

    @Test fun aCallCapableHeadsetUsesItsOwnMic() {
        val r = AudioRoutes.report(listOf(D.TYPE_BLUETOOTH_SCO, D.TYPE_BUILTIN_MIC), listOf(D.TYPE_BLUETOOTH_SCO))
        assertEquals(RouteKind.BluetoothCall, r.input)
        assertEquals(RouteKind.BluetoothCall, r.output)
        assertFalse(r.headsetMicNotUsed)
    }

    @Test fun phoneOnlyPlaysThroughTheLoudspeaker() {
        val r = AudioRoutes.report(listOf(D.TYPE_BUILTIN_MIC), listOf(D.TYPE_BUILTIN_SPEAKER, D.TYPE_BUILTIN_EARPIECE))
        assertTrue(r.outputIsLoudspeaker)
        assertFalse(r.headsetMicNotUsed)
        assertFalse(r.noOutput)
    }

    @Test fun wiredHeadsetWinsOverThePhone() {
        val r = AudioRoutes.report(listOf(D.TYPE_WIRED_HEADSET, D.TYPE_BUILTIN_MIC), listOf(D.TYPE_WIRED_HEADSET, D.TYPE_BUILTIN_SPEAKER))
        assertEquals(RouteKind.Wired, r.input)
        assertEquals(RouteKind.Wired, r.output)
        assertFalse(r.outputIsLoudspeaker)
    }

    @Test fun leAudioIsPreferredAndHasItsOwnMic() {
        val r = AudioRoutes.report(listOf(D.TYPE_BLE_HEADSET), listOf(D.TYPE_BLE_HEADSET, D.TYPE_BLUETOOTH_A2DP))
        assertEquals(RouteKind.BluetoothLe, r.input)
        assertEquals(RouteKind.BluetoothLe, r.output)
        assertFalse(r.headsetMicNotUsed)
    }

    @Test fun usbHeadsetIsRecognised() {
        val r = AudioRoutes.report(listOf(D.TYPE_USB_HEADSET), listOf(D.TYPE_USB_HEADSET))
        assertEquals(RouteKind.Usb, r.input)
        assertEquals(RouteKind.Usb, r.output)
    }

    @Test fun nothingConnectedIsReported() {
        val r = AudioRoutes.report(emptyList(), emptyList())
        assertEquals(RouteKind.None, r.input)
        assertTrue(r.noOutput)
        assertFalse(r.outputIsLoudspeaker)
    }

    @Test fun unknownTypesAreIgnored() {
        assertEquals(RouteKind.None, AudioRoutes.kindOf(9999))
    }
}

class LevelMeterTest {
    private fun frame(amplitude: Int, n: Int = 320) = ShortArray(n) { amplitude.toShort() }

    @Test fun silenceIsZero() {
        assertEquals(0f, LevelMeter().level(frame(0)), 0f)
        assertEquals(0f, LevelMeter().level(ShortArray(0)), 0f)
    }

    @Test fun fullScaleIsOne() {
        assertEquals(1f, LevelMeter().level(frame(32767)), 0.01f)
    }

    @Test fun minusTwentyDbIsTwoThirds() {
        // 0.1 of full scale is -20 dBFS: (-20 + 60) / 60.
        assertEquals(0.667f, LevelMeter().level(frame(3277)), 0.01f)
    }

    @Test fun belowTheFloorIsZero() {
        assertEquals(0f, LevelMeter().level(frame(20)), 0.001f)
    }

    @Test fun peakIsHeldThenFalls() {
        val m = LevelMeter(holdFrames = 15)
        val loud = m.push(frame(3277))
        repeat(15) { m.push(frame(0)) }
        assertEquals(loud, m.peakHold, 0f)
        m.push(frame(0))
        assertTrue(m.peakHold < loud)
    }

    @Test fun heardVoiceNeedsALevelAboveAQuietRoom() {
        val m = LevelMeter()
        m.push(frame(30))
        assertFalse(m.heardVoice())
        m.push(frame(1000))
        assertTrue(m.heardVoice())
    }

    @Test fun clippingIsDetected() {
        val m = LevelMeter()
        assertTrue(m.clipped(frame(32767)))
        assertFalse(m.clipped(frame(1000)))
    }
}
