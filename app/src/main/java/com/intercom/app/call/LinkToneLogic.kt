package com.intercom.app.call

import com.intercom.app.transport.LinkState

enum class LinkTone { Lost, Restored }

/**
 * Decides when to play the "link lost" and "link restored" tones.
 *
 * A tone is for a connection that was up and dropped, and for that same connection coming
 * back. It is not for the first connect, not for a deliberate switch between Bluetooth and
 * Wi-Fi (the rider asked, or the app did it on purpose and says so), and a link that flaps
 * does not machine-gun the rider: a second "lost" inside [LOST_GAP_MS] is silent, and a
 * "restored" only plays if a "lost" was played for that drop.
 */
class LinkToneLogic {
    private var wasConnected = false
    private var lostPlayed = false
    private var lastLostAt = Long.MIN_VALUE / 2

    fun onState(state: LinkState, switching: Boolean, nowMs: Long): LinkTone? {
        when (state) {
            LinkState.Connected -> {
                val tone = if (lostPlayed) LinkTone.Restored else null
                lostPlayed = false
                wasConnected = true
                return tone
            }
            LinkState.Reconnecting -> {
                if (!wasConnected || lostPlayed || switching) return null
                if (nowMs - lastLostAt < LOST_GAP_MS) return null
                lostPlayed = true
                lastLostAt = nowMs
                return LinkTone.Lost
            }
            LinkState.Closed, LinkState.Failed, LinkState.Idle -> {
                wasConnected = false
                lostPlayed = false
                return null
            }
            else -> return null
        }
    }

    fun reset() {
        wasConnected = false
        lostPlayed = false
    }

    companion object {
        const val LOST_GAP_MS = 10_000L
    }
}
