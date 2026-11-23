package com.intercom.app.util

import com.intercom.app.audio.Beeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkiePolicyTest {

    @Test fun walkieIsAlwaysPushToTalk() {
        assertEquals(TalkMode.PushToTalk, WalkiePolicy.talkMode(true, TalkMode.Open))
        assertEquals(TalkMode.PushToTalk, WalkiePolicy.talkMode(true, TalkMode.PushToTalk))
    }

    @Test fun outsideWalkieTheRidersChoiceStands() {
        assertEquals(TalkMode.Open, WalkiePolicy.talkMode(false, TalkMode.Open))
        assertEquals(TalkMode.PushToTalk, WalkiePolicy.talkMode(false, TalkMode.PushToTalk))
    }

    @Test fun walkieImpliesAGroupAndNeverUsesTheRelay() {
        assertTrue(WalkiePolicy.isGroup(true, false))
        assertTrue(WalkiePolicy.isGroup(false, true))
        assertFalse(WalkiePolicy.isGroup(false, false))
        assertFalse(WalkiePolicy.useInternet(true, true))
        assertTrue(WalkiePolicy.useInternet(false, true))
        assertFalse(WalkiePolicy.useInternet(false, false))
    }

    @Test fun rogerBeepIsAlwaysOnInWalkieAndSkipsTheGap() {
        assertTrue(WalkiePolicy.beepWhenChannelFree(true, false))
        assertFalse(WalkiePolicy.beepWhenChannelFree(false, false))
        assertTrue(WalkiePolicy.beepWhenChannelFree(false, true))
        assertTrue(WalkiePolicy.beepIgnoresGap(true))
        assertFalse(WalkiePolicy.beepIgnoresGap(false))
    }

    @Test fun settingsExtensionsFollowThePolicy() {
        val s = Settings(walkieMode = true, internetRide = true, talkMode = TalkMode.Open, groupRide = false)
        assertTrue(s.isGroup)
        assertFalse(s.useInternet)
        assertEquals(TalkMode.PushToTalk, s.effectiveTalkMode)
        val plain = Settings(walkieMode = false, internetRide = true, talkMode = TalkMode.Open)
        assertFalse(plain.isGroup)
        assertTrue(plain.useInternet)
        assertEquals(TalkMode.Open, plain.effectiveTalkMode)
    }

    @Test fun walkieIsOffByDefault() {
        assertFalse(Settings().walkieMode)
    }

    @Test fun backToBackRogerBeepsAreNotSwallowedByTheUsualGap() {
        val b = Beeper()
        assertTrue(b.request())
        assertFalse("the normal 2 s gap holds", b.request())
        assertTrue("walkie skips it", b.request(ignoreGap = true))
        assertTrue(b.isActive)
    }
}
