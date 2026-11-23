package com.intercom.app.group

import com.intercom.app.audio.AudioConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMixerTest {
    private var now = 0L
    private val made = mutableListOf<FakeCodec>()
    private var duck: (Int) -> Float = { 1f }

    private fun mixer(max: Int = 3) = GroupMixer(
        newDecoder = { flag -> if (flag == 0 || flag == 1) FakeCodec(flag).also { made += it } else null },
        maxStreams = max,
        jitterTarget = 1,
        duckFactor = { duck(it) },
        now = { now },
    )

    private val out = ShortArray(AudioConstants.FRAME_SAMPLES)

    /** One frame whose decoded value will be [v] * 100. */
    private fun GroupMixer.say(sender: Int, seq: Int, v: Int, flag: Int = 0) =
        put(sender, seq, flag, byteArrayOf(v.toByte()))

    // ---- summing ----------------------------------------------------------------

    @Test fun anEmptyMixerHasNothingToPlay() {
        assertFalse(mixer().take(out))
    }

    @Test fun oneRiderPassesThroughBitExact() {
        val m = mixer()
        m.say(1, 0, 10)
        assertTrue(m.take(out))
        assertTrue(out.all { it == 1000.toShort() })
    }

    @Test fun twoRidersAreSummed() {
        val m = mixer()
        m.say(1, 0, 10); m.say(2, 0, 20)
        assertTrue(m.take(out))
        assertTrue(out.all { it == 3000.toShort() })
    }

    @Test fun loudVoicesAreSoftClippedNotWrapped() {
        val m = mixer()
        // 100 * 100 = 10000 each; three of them: 30000 against a 24576 knee.
        m.say(1, 0, 100); m.say(2, 0, 100); m.say(3, 0, 100)
        assertTrue(m.take(out))
        for (s in out) {
            assertTrue("sample $s", s in 24577..32767)
        }
    }

    @Test fun opposingVoicesCancelRatherThanOverflow() {
        val m = mixer()
        m.say(1, 0, 100); m.say(2, 0, -100)
        m.take(out)
        assertTrue(out.all { it == 0.toShort() })
    }

    // ---- per-rider state --------------------------------------------------------

    @Test fun eachRiderGetsItsOwnDecoder() {
        val m = mixer()
        m.say(1, 0, 10); m.say(2, 0, 20)
        m.take(out)
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
        // Each decoder saw only its own rider's frames.
        assertEquals(listOf<Byte>(10), made.first { it.firstBytes.contains(10) }.firstBytes)
        assertEquals(listOf<Byte>(20), made.first { it.firstBytes.contains(20) }.firstBytes)
    }

    @Test fun aRidersDecoderIsReusedAcrossFrames() {
        val m = mixer()
        m.say(1, 0, 10); m.take(out)
        m.say(1, 1, 10); m.take(out)
        assertEquals(1, made.size)
        assertEquals(2, made[0].decoded)
    }

    @Test fun aChangeOfCodecSwapsTheDecoderAndReleasesTheOld() {
        val m = mixer()
        m.say(1, 0, 10, flag = 0); m.take(out)
        m.say(1, 1, 10, flag = 1); m.take(out)
        assertEquals(2, made.size)
        assertTrue(made[0].released)
        assertFalse(made[1].released)
        assertEquals(1, made[1].flag)
    }

    @Test fun anUnknownCodecIsDroppedAndCounted() {
        val m = mixer()
        assertFalse(m.say(1, 0, 10, flag = 9))
        assertEquals(1L, m.droppedUnknownCodec)
        assertEquals(0, m.streamCount)
    }

    @Test fun aCodecThisPhoneCannotRunIsCountedNotPlayed() {
        val m = GroupMixer(newDecoder = { null }, jitterTarget = 1, now = { now })
        m.say(1, 0, 10, flag = 1)
        assertFalse(m.take(out))
        assertEquals(1L, m.droppedUnknownCodec)
    }

    // ---- the cap on simultaneous voices -----------------------------------------

    @Test fun aFourthSimultaneousRiderIsDroppedAndCounted() {
        val m = mixer(max = 3)
        assertTrue(m.say(1, 0, 1)); assertTrue(m.say(2, 0, 1)); assertTrue(m.say(3, 0, 1))
        assertFalse(m.say(4, 0, 1))
        assertEquals(1L, m.droppedOverCap)
        assertEquals(3, m.activeStreams())
        m.take(out)
        assertEquals(3, made.size) // the fourth never got a decoder
    }

    @Test fun existingRidersKeepTalkingWhenTheCapIsFull() {
        val m = mixer(max = 3)
        m.say(1, 0, 1); m.say(2, 0, 1); m.say(3, 0, 1)
        assertTrue(m.say(1, 1, 1))
    }

    @Test fun aSlotFreesWhenARiderGoesQuiet() {
        val m = mixer(max = 3)
        m.say(1, 0, 1); m.say(2, 0, 1); m.say(3, 0, 1)
        m.take(out) // drains one frame each
        now += GroupMixer.ACTIVE_WINDOW_MS + 1
        assertTrue(m.say(4, 0, 1))
        assertEquals(1, m.activeStreams())
    }

    @Test fun aLongIdleRiderReturningNeedsAFreeSlotToo() {
        val m = mixer(max = 2)
        m.say(1, 0, 1); m.say(2, 0, 1)
        m.take(out)
        now += GroupMixer.ACTIVE_WINDOW_MS + 1
        m.say(3, 0, 1); m.say(4, 0, 1) // two others now hold both slots
        assertFalse(m.say(1, 1, 1))
        assertEquals(1L, m.droppedOverCap)
    }

    @Test fun idleStreamsAreEvictedAndTheirDecodersReleased() {
        val m = mixer()
        m.say(1, 0, 10); m.take(out)
        now += GroupMixer.EVICT_AFTER_MS + 1
        m.take(out)
        assertEquals(0, m.streamCount)
        assertTrue(made[0].released)
    }

    @Test fun releaseFreesEveryDecoder() {
        val m = mixer()
        m.say(1, 0, 1); m.say(2, 0, 1); m.take(out)
        m.release()
        assertTrue(made.all { it.released })
        assertEquals(0, m.streamCount)
    }

    // ---- gain, mute, ducking ----------------------------------------------------

    @Test fun perRiderGainScalesTheirVoice() {
        val m = mixer()
        m.setGain(1, 0.5f)
        m.say(1, 0, 10); m.take(out)    // ramps down across this frame
        m.say(1, 1, 10); m.take(out)    // steady
        assertTrue(out.all { it == 500.toShort() })
    }

    @Test fun gainRampsAcrossAFrameInsteadOfSteppingAtTheStart() {
        val m = mixer()
        m.say(1, 0, 10); m.take(out)    // gain 1
        m.setGain(1, 0f)
        m.say(1, 1, 10); m.take(out)
        assertTrue("starts near full", out.first() > 900)
        assertEquals(0, out.last().toInt())
        assertTrue("monotonic", out.toList().zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun gainIsClampedToZeroAndTwo() {
        val m = mixer()
        m.setGain(1, 9f); assertEquals(2f, m.gainOf(1), 0f)
        m.setGain(1, -1f); assertEquals(0f, m.gainOf(1), 0f)
        assertEquals(1f, m.gainOf(99), 0f)
    }

    @Test fun aMutedRidersFramesAreDroppedBeforeAnyDecoding() {
        val m = mixer()
        m.setMuted(1, true)
        assertFalse(m.say(1, 0, 10))
        assertEquals(1L, m.droppedMuted)
        assertFalse(m.take(out))
        assertEquals(0, made.size)
    }

    @Test fun unmutingBringsTheRiderBack() {
        val m = mixer()
        m.setMuted(1, true)
        m.say(1, 0, 10)
        m.setMuted(1, false)
        assertTrue(m.say(1, 1, 10))
        assertTrue(m.take(out))
        assertFalse(m.isMuted(1))
    }

    @Test fun muteIsRememberedWhenTheStreamIsRecreated() {
        val m = mixer()
        m.say(1, 0, 10); m.take(out)
        m.setMuted(1, true)
        now += GroupMixer.EVICT_AFTER_MS + 1
        m.take(out)
        assertEquals(0, m.streamCount)
        assertFalse(m.say(1, 5, 10))
    }

    @Test fun duckingScalesNonHoldersButDoesNotSilenceThem() {
        val m = mixer()
        duck = { id -> if (id == 2) 0.3f else 1f }
        m.say(1, 0, 10); m.say(2, 0, 10); m.take(out)   // ramp frame
        m.say(1, 1, 10); m.say(2, 1, 10); m.take(out)   // steady: 1000 + 300
        for (s in out) assertEquals(1300.0, s.toDouble(), 1.0)
    }

    // ---- queries ----------------------------------------------------------------

    @Test fun speakingFollowsRecentAudio() {
        val m = mixer()
        assertFalse(m.speaking(1))
        m.say(1, 0, 10)
        assertTrue(m.speaking(1))
        m.take(out)
        now += GroupMixer.ACTIVE_WINDOW_MS + 1
        assertFalse(m.speaking(1))
    }

    @Test fun lossIsZeroUntilThereIsEnoughToJudgeBy() {
        val m = mixer()
        m.say(1, 0, 10); m.take(out)
        assertEquals(0.0, m.lossPercent(1), 0.0)
        assertEquals(0.0, m.lossPercent(99), 0.0)
    }

    @Test fun sequenceNumbersWrapAtSixteenBits() {
        val m = mixer()
        assertTrue(m.say(1, 0xFFFF, 10))
        assertTrue(m.say(1, 0x10000, 10)) // masked to 0
        assertTrue(m.take(out))
    }
}
