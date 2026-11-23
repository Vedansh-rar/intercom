package com.intercom.app.security

/**
 * Sliding window over received frame counters, as IPsec and DTLS do it.
 *
 * Accepts a counter once. Anything older than the window, or already seen, is refused.
 * Frames travel over ordered streams here, so reordering is rare, but a link switch can
 * deliver a few out of order and the window tolerates that without opening a replay hole.
 */
class ReplayWindow(private val size: Int = 64) {
    init { require(size in 1..64) }

    private var highest = -1L
    private var bitmap = 0L // bit i set = (highest - i) has been seen

    /** True and records [counter] if it is new; false if replayed or too old. */
    @Synchronized
    fun accept(counter: Long): Boolean {
        require(counter >= 0)
        if (highest < 0) {
            highest = counter
            bitmap = 1L
            return true
        }
        if (counter > highest) {
            val shift = counter - highest
            bitmap = if (shift >= 64) 1L else (bitmap shl shift.toInt()) or 1L
            highest = counter
            return true
        }
        val back = highest - counter
        if (back >= size) return false
        val bit = 1L shl back.toInt()
        if (bitmap and bit != 0L) return false
        bitmap = bitmap or bit
        return true
    }

    @Synchronized
    fun reset() {
        highest = -1L
        bitmap = 0L
    }
}
