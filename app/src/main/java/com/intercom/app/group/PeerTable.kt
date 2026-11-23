package com.intercom.app.group

import java.util.concurrent.ConcurrentHashMap

/** A rough health label for the peer list. */
enum class LinkQuality { Unknown, Poor, Fair, Good }

/**
 * One rider in the group, as the UI shows them.
 *
 * @property hops 1 for a rider this phone is directly connected to, more if their audio
 *   arrives through relays.
 */
data class GroupPeer(
    val id: Int,
    val name: String,
    val hops: Int,
    val priority: Int,
    val quality: LinkQuality,
    val speaking: Boolean,
    val muted: Boolean,
    val lastHeardMs: Long,
) {
    val isHost: Boolean get() = priority > 0
}

/**
 * Everyone this phone knows to be in the group ride.
 *
 * Built from what arrives, not from a roster: any envelope from a rider refreshes them,
 * and a PEER_INFO beacon (every ~5 s, relayed like everything else) gives their name and
 * priority. A rider who is silent for [expiryMs], or whose only route was a connection
 * that dropped, is removed; that is how "someone left mid-ride" is noticed, whether or not
 * they said goodbye.
 *
 * Thread-safe. The table is a [ConcurrentHashMap] of immutable entries updated with
 * `compute`, and the mute set and endpoint bindings are concurrent collections, so the
 * receive thread, the timer and the UI thread can all touch it without a lock.
 */
class PeerTable(
    private val now: () -> Long = System::currentTimeMillis,
    private val expiryMs: Long = EXPIRY_MS,
) {
    private class Entry(
        val id: Int,
        val name: String,
        val hops: Int,
        val via: String?,
        val priority: Int,
        val lastHeard: Long,
        val routeSince: Long,
    )

    private val peers = ConcurrentHashMap<Int, Entry>()
    private val mutedIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Records that something from [id] arrived over [hops] hops through endpoint [via]. */
    fun heard(id: Int, hops: Int, via: String?) {
        val t = now()
        peers.compute(id) { _, old ->
            when {
                old == null -> Entry(id, DEFAULT_NAME, hops, via, 0, t, t)
                // Keep the shortest route, but let a longer one take over once the short
                // one has gone quiet.
                hops <= old.hops || t - old.lastHeard > ROUTE_SWITCH_MS ->
                    Entry(id, old.name, hops, via, old.priority, t, t)
                else -> Entry(id, old.name, old.hops, old.via, old.priority, t, old.routeSince)
            }
        }
    }

    /** Name and priority from a PEER_INFO beacon. */
    fun info(id: Int, name: String, priority: Int) {
        peers.computeIfPresent(id) { _, o ->
            Entry(o.id, name.ifBlank { DEFAULT_NAME }, o.hops, o.via, priority.coerceIn(0, 255), o.lastHeard, o.routeSince)
        }
    }

    fun nameOf(id: Int): String = peers[id]?.name ?: DEFAULT_NAME
    fun priorityOf(id: Int): Int = peers[id]?.priority ?: 0
    fun contains(id: Int): Boolean = peers.containsKey(id)
    val size: Int get() = peers.size

    fun setMuted(id: Int, muted: Boolean) {
        if (muted) mutedIds.add(id) else mutedIds.remove(id)
    }

    fun isMuted(id: Int) = mutedIds.contains(id)

    /** Riders not heard from for [expiryMs]. Removed; returns their IDs. */
    fun expire(): List<Int> {
        val t = now()
        val gone = ArrayList<Int>()
        for ((id, e) in peers) if (t - e.lastHeard > expiryMs) gone.add(id)
        return gone.filter { peers.remove(it) != null }
    }

    /**
     * A direct connection dropped. Everyone who was reached through [endpoint] is gone
     * until they are heard again by another route (which takes one beacon).
     */
    fun removeVia(endpoint: String): List<Int> {
        val gone = peers.values.filter { it.via == endpoint }.map { it.id }
        return gone.filter { peers.remove(it) != null }
    }

    fun remove(id: Int): Boolean = peers.remove(id) != null

    fun clear() = peers.clear()

    /**
     * The list the UI draws, sorted host first then by name. [speaking] and [lossPercent]
     * come from the mixer; [playedFrames] says whether there is loss data worth trusting.
     */
    fun snapshot(
        speaking: (Int) -> Boolean,
        lossPercent: (Int) -> Double,
        hasLossData: (Int) -> Boolean,
    ): List<GroupPeer> {
        val t = now()
        return peers.values.map { e ->
            val age = t - e.lastHeard
            GroupPeer(
                id = e.id,
                name = e.name,
                hops = e.hops,
                priority = e.priority,
                quality = qualityOf(e.hops, age, hasLossData(e.id), lossPercent(e.id)),
                speaking = speaking(e.id),
                muted = mutedIds.contains(e.id),
                lastHeardMs = e.lastHeard,
            )
        }.sortedWith(compareByDescending<GroupPeer> { it.priority }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }

    companion object {
        const val EXPIRY_MS = 15_000L
        const val ROUTE_SWITCH_MS = 3_000L
        const val DEFAULT_NAME = "Rider"

        /**
         * Quality from what is known. With enough audio, loss decides; otherwise how
         * recently the rider's beacons arrived, and whether they come through a relay.
         */
        fun qualityOf(hops: Int, ageMs: Long, hasLossData: Boolean, lossPercent: Double): LinkQuality {
            if (ageMs > 10_000) return LinkQuality.Poor
            if (hasLossData) {
                return when {
                    lossPercent >= 10.0 -> LinkQuality.Poor
                    lossPercent >= 3.0 || hops > 1 -> LinkQuality.Fair
                    else -> LinkQuality.Good
                }
            }
            return if (ageMs > 7_000) LinkQuality.Poor else if (hops > 1) LinkQuality.Fair else LinkQuality.Good
        }
    }
}
