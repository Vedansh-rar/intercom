package com.intercom.app.group

import com.intercom.app.security.ReplayWindow

/**
 * "Have I seen this before?", per sender, for a flood that reaches a phone by more than one
 * route. One sliding [ReplayWindow] per sender ID, so a late copy of an old message is
 * refused as well as an exact repeat.
 *
 * Thread-safe: it is called from the link's receive thread and, for the sender's own
 * traffic, from others. The table is bounded so a peer inventing sender IDs cannot grow it
 * without limit; the least recently seen sender is dropped first.
 */
class DedupWindow(private val maxSenders: Int = 32) {

    private val windows = object : LinkedHashMap<Int, ReplayWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ReplayWindow>?) =
            size > maxSenders
    }

    /** True the first time (sender, seq) is seen; false for a repeat or an old straggler. */
    @Synchronized
    fun firstSighting(senderId: Int, seq: Long): Boolean =
        windows.getOrPut(senderId) { ReplayWindow(WINDOW) }.accept(seq)

    @Synchronized
    fun forget(senderId: Int) {
        windows.remove(senderId)
    }

    @Synchronized
    fun clear() = windows.clear()

    @get:Synchronized
    val trackedSenders: Int get() = windows.size

    companion object {
        const val WINDOW = 64
    }
}
