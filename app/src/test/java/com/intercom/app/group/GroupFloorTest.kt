package com.intercom.app.group

import com.intercom.app.floor.FloorState
import com.intercom.app.group.GroupFloor.Event
import com.intercom.app.group.GroupFloor.LocalResult
import com.intercom.app.group.GroupFloor.RemoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupFloorTest {
    private val me = 100
    private fun floor(priority: Int = 0) = GroupFloor(me, priority)

    // ---- the quiet channel and the first speaker -------------------------------

    @Test fun anIdleChannelIsGrantedToTheFirstLocalSpeaker() {
        val f = floor()
        assertEquals(FloorState.Idle, f.state())
        assertEquals(LocalResult.NewlyGranted, f.localSpeaking(0, "Me"))
        assertEquals(FloorState.Talking, f.state())
        assertEquals(LocalResult.Granted, f.localSpeaking(20, "Me"))
    }

    @Test fun theFirstRemoteSpeakerHoldsTheFloor() {
        val f = floor()
        assertEquals(RemoteResult.Accepted, f.remoteClaim(7, 0, "Asha", 0))
        assertEquals(FloorState.Muted, f.state())
        assertEquals(7, f.holderId())
        assertEquals("Asha", f.holderName())
    }

    @Test fun aSecondEqualSpeakerLaterDoesNotTakeTheFloor() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        f.remoteClaim(7, 0, "Asha", 400) // keep her fresh
        assertEquals(RemoteResult.Rejected, f.remoteClaim(3, 0, "Ben", 500))
        assertEquals(7, f.holderId())
    }

    @Test fun aLocalSpeakerWhoCannotTakeTheFloorIsWaitingNotTransmitting() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(LocalResult.Waiting, f.localSpeaking(10, "Me"))
        assertTrue(f.isWaiting())
        assertEquals(7, f.holderId())
        assertFalse(f.selfHolds())
    }

    // ---- priority ---------------------------------------------------------------

    @Test fun aHigherPriorityLocalSpeakerPreemptsAnEarlierSpeaker() {
        val f = floor(priority = 1)
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(LocalResult.Preempted, f.localSpeaking(1_000, "Host"))
        assertTrue(f.selfHolds())
        assertFalse(f.isWaiting())
    }

    @Test fun anEqualPriorityLocalSpeakerDoesNotPreempt() {
        val f = floor(priority = 1)
        f.remoteClaim(7, 1, "Other host", 0)
        assertEquals(LocalResult.Waiting, f.localSpeaking(1_000, "Me"))
    }

    @Test fun aHigherPriorityRemoteTakesTheFloorFromUsAndTellsUsToYield() {
        val f = floor(priority = 0)
        f.localSpeaking(0, "Me")
        assertEquals(RemoteResult.LocalYielded, f.remoteClaim(7, 1, "Host", 5_000))
        assertEquals(7, f.holderId())
        assertFalse(f.selfHolds())
    }

    @Test fun aHigherPriorityRemoteReplacesALowerRemote() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(RemoteResult.Accepted, f.remoteClaim(8, 1, "Host", 2_000))
        assertEquals(8, f.holderId())
    }

    @Test fun aLowerPriorityRemoteNeverTakesTheFloorFromUs() {
        val f = floor(priority = 1)
        f.localSpeaking(0, "Host")
        assertEquals(RemoteResult.Rejected, f.remoteClaim(7, 0, "Asha", 50))
        assertTrue(f.selfHolds())
    }

    // ---- simultaneous starts ----------------------------------------------------

    @Test fun simultaneousEqualClaimsAreSettledByTheLowerSenderId() {
        val f = floor()
        f.remoteClaim(900, 0, "High id", 0)
        assertEquals(RemoteResult.Accepted, f.remoteClaim(50, 0, "Low id", 100))
        assertEquals(50, f.holderId())
    }

    @Test fun theHigherIdLosesASimultaneousClaim() {
        val f = floor()
        f.remoteClaim(50, 0, "Low id", 0)
        assertEquals(RemoteResult.Rejected, f.remoteClaim(900, 0, "High id", 100))
        assertEquals(50, f.holderId())
    }

    @Test fun idsAreComparedAsUnsigned() {
        val f = floor()
        f.remoteClaim(-1, 0, "Huge id", 0) // 0xFFFFFFFF
        assertEquals(RemoteResult.Accepted, f.remoteClaim(5, 0, "Small id", 50))
    }

    @Test fun everyPhoneReachesTheSameVerdictWhateverOrderItHearsTheClaims() {
        // Three phones see the same two simultaneous claims in opposite orders.
        val a = GroupFloor(1, 0)
        val b = GroupFloor(2, 0)
        a.remoteClaim(10, 0, "X", 0); a.remoteClaim(20, 0, "Y", 50)
        b.remoteClaim(20, 0, "Y", 0); b.remoteClaim(10, 0, "X", 50)
        assertEquals(a.holderId(), b.holderId())
        assertEquals(10, a.holderId())
    }

    @Test fun twoLocalAndRemoteSimultaneousStartsAgreeWithTheRemotePhone() {
        // Me (id 100) and a remote (id 40) start together. The remote has the lower id.
        val mine = GroupFloor(100, 0)
        mine.localSpeaking(0, "Me")
        assertEquals(RemoteResult.LocalYielded, mine.remoteClaim(40, 0, "Them", 100))

        val theirs = GroupFloor(40, 0)
        theirs.localSpeaking(0, "Them")
        assertEquals(RemoteResult.Rejected, theirs.remoteClaim(100, 0, "Me", 100))
        assertEquals(40, theirs.holderId())
        assertEquals(40, mine.holderId())
    }

    // ---- releasing, going stale, leaving ----------------------------------------

    @Test fun localStoppedFreesTheFloorOnlyIfWeHeldIt() {
        val f = floor()
        f.localSpeaking(0, "Me")
        assertTrue(f.localStopped())
        assertEquals(FloorState.Idle, f.state())
        f.remoteClaim(7, 0, "Asha", 0)
        assertFalse(f.localStopped())
        assertEquals(7, f.holderId())
    }

    @Test fun aRemoteReleaseFreesTheFloor() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(Event.Released(7).holderId, (f.remoteReleased(7) as Event.Released).holderId)
        assertEquals(FloorState.Idle, f.state())
    }

    @Test fun aReleaseFromSomeoneWhoDoesNotHoldItIsIgnored() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertNull(f.remoteReleased(8))
        assertEquals(7, f.holderId())
    }

    @Test fun aSilentRemoteHolderGoesStale() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertNull(f.tick(GroupFloor.HOLDER_STALE_MS, false))
        assertTrue(f.tick(GroupFloor.HOLDER_STALE_MS + 1, false) is Event.Released)
        assertEquals(FloorState.Idle, f.state())
    }

    @Test fun anyAudioFromTheHolderKeepsItFresh() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        f.remoteClaim(7, 0, "Asha", 1_500)
        assertNull(f.tick(3_000, false))
        assertEquals(7, f.holderId())
    }

    @Test fun aStaleHolderDoesNotBlockANewClaim() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(RemoteResult.Accepted, f.remoteClaim(8, 0, "Ben", 10_000))
        assertEquals(8, f.holderId())
    }

    @Test fun aLocalSpeakerTakesAStaleFloor() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(LocalResult.NewlyGranted, f.localSpeaking(10_000, "Me"))
    }

    @Test fun anOpenMicHolderIsCutOffAfterTheMaximumHold() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        for (t in 500L..GroupFloor.MAX_HOLD_MS step 500L) f.remoteClaim(7, 0, "Asha", t)
        assertTrue(f.tick(GroupFloor.MAX_HOLD_MS + 1, pushToTalk = false) is Event.Released)
    }

    @Test fun pushToTalkHasNoMaximumHold() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        for (t in 500L..40_000L step 500L) f.remoteClaim(7, 0, "Asha", t)
        assertNull(f.tick(40_001, pushToTalk = true))
    }

    @Test fun theLocalHolderIsNeverStaleForWantOfRemoteAudio() {
        val f = floor()
        f.localSpeaking(0, "Me")
        assertNull(f.tick(GroupFloor.HOLDER_STALE_MS * 3, pushToTalk = true))
        assertTrue(f.selfHolds())
    }

    @Test fun whenTheHolderLeavesTheFloorIsFree() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertTrue(f.peerLeft(7) is Event.Released)
        assertEquals(FloorState.Idle, f.state())
        assertNull(f.peerLeft(7))
    }

    @Test fun aRiderWhoIsNotTheHolderLeavingChangesNothing() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertNull(f.peerLeft(9))
        assertEquals(7, f.holderId())
    }

    // ---- queueing ---------------------------------------------------------------

    @Test fun aWaitingRiderIsToldWhenTheChannelIsFree() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        f.localSpeaking(100, "Me")
        assertTrue(f.isWaiting())
        assertTrue(f.remoteReleased(7) is Event.ChannelFree)
        assertFalse(f.isWaiting())
        assertEquals(LocalResult.NewlyGranted, f.localSpeaking(200, "Me"))
    }

    @Test fun aWaitingRiderIsToldWhenTheHolderGoesStale() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        f.localSpeaking(100, "Me")
        assertTrue(f.tick(5_000, false) is Event.ChannelFree)
    }

    @Test fun aRiderWhoNeverWaitedGetsNoChannelFreeCue() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertTrue(f.remoteReleased(7) is Event.Released)
    }

    @Test fun beingYieldedToAHigherPriorityRiderThenWaiting() {
        val f = floor(priority = 0)
        f.localSpeaking(0, "Me")
        f.remoteClaim(7, 1, "Host", 5_000)
        assertEquals(LocalResult.Waiting, f.localSpeaking(5_020, "Me"))
    }

    // ---- ducking ----------------------------------------------------------------

    @Test fun everyoneIsFullVolumeWhenNobodyHoldsTheFloor() {
        val f = floor()
        assertEquals(1f, f.duckFactor(7), 0f)
    }

    @Test fun theHolderIsFullVolumeAndOthersAreDuckedNotMuted() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        assertEquals(1f, f.duckFactor(7), 0f)
        assertEquals(GroupFloor.DUCK_GAIN, f.duckFactor(8), 0f)
        assertTrue(f.duckFactor(8) > 0f)
    }

    @Test fun whenWeHoldTheFloorEveryRemoteVoiceIsDucked() {
        val f = floor()
        f.localSpeaking(0, "Me")
        assertEquals(GroupFloor.DUCK_GAIN, f.duckFactor(7), 0f)
    }

    @Test fun ourOwnClaimEchoedBackIsIgnored() {
        val f = floor()
        assertEquals(RemoteResult.Rejected, f.remoteClaim(me, 1, "Me", 0))
        assertEquals(FloorState.Idle, f.state())
    }

    @Test fun resetClearsEverything() {
        val f = floor()
        f.remoteClaim(7, 0, "Asha", 0)
        f.localSpeaking(10, "Me")
        f.reset()
        assertEquals(FloorState.Idle, f.state())
        assertFalse(f.isWaiting())
    }
}
