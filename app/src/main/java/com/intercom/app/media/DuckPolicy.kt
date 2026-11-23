package com.intercom.app.media

import com.intercom.app.floor.FloorState

/**
 * When the rider's music is lowered.
 *
 * Music is ducked while the buddy is speaking (the floor is [FloorState.Muted] on this
 * phone) so their voice is clear over it, and given back after a short hold-off when they
 * stop. It is not ducked for the rider's own speech unless they ask for that, and never
 * while deafened (nothing is being played to them, so there is nothing to make room for).
 */
object DuckPolicy {
    fun shouldDuck(floor: FloorState, duckWhenITalk: Boolean, deafened: Boolean): Boolean {
        if (deafened) return false
        return when (floor) {
            FloorState.Muted -> true
            FloorState.Talking -> duckWhenITalk
            FloorState.Idle -> false
        }
    }
}
