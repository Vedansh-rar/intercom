package com.intercom.app.transport

/**
 * Pure decision logic pulled out of [WifiDirectLink] so it can be unit-tested without a
 * phone. Nothing in here touches Android classes or the clock: callers pass time in.
 */

/** A peer seen during Wi-Fi Direct discovery, reduced to what the choice needs. */
data class PeerCandidate(val address: String, val isGroupOwner: Boolean)

object PeerPicker {
    /**
     * The joiner wants the host. The host advertises itself as an autonomous group owner,
     * so a group owner wins; otherwise the first device is tried. Blank addresses are
     * ignored. Returns null when nothing usable has been seen yet.
     */
    fun pick(peers: List<PeerCandidate>): String? {
        val usable = peers.filter { it.address.isNotBlank() }
        return (usable.firstOrNull { it.isGroupOwner } ?: usable.firstOrNull())?.address
    }
}

/**
 * Stops the joiner from issuing `connect()` on top of a connection that is still
 * negotiating. Re-issuing it every discovery cycle cancels the invitation in flight on
 * many phones and produces the "Invited" state that never resolves.
 */
class ConnectGate(private val minIntervalMs: Long = 12_000L) {
    private var lastAttemptAtMs: Long = Long.MIN_VALUE
    private var lastAddress: String? = null

    /** True when a fresh `connect()` to [address] may be issued at [nowMs]. */
    fun mayConnect(address: String, nowMs: Long): Boolean {
        if (lastAttemptAtMs == Long.MIN_VALUE) return true
        if (address != lastAddress) return true
        return nowMs - lastAttemptAtMs >= minIntervalMs
    }

    /** True once any connect has been issued since the last [reset]. */
    val hasAttempted: Boolean get() = lastAttemptAtMs != Long.MIN_VALUE

    fun recordAttempt(address: String, nowMs: Long) {
        lastAttemptAtMs = nowMs
        lastAddress = address
    }

    fun reset() {
        lastAttemptAtMs = Long.MIN_VALUE
        lastAddress = null
    }
}

/**
 * Retry schedule for the joiner's TCP control connection to the group owner.
 *
 * The group forms on both phones at slightly different moments, so the joiner often
 * reaches the owner's address before the owner is listening. A single refused connect
 * used to throw all the way out to the supervisor, which then restarted discovery on a
 * group that already existed. Retrying the connect itself, briefly, is the right size of
 * response.
 */
class ConnectRetryPolicy(
    private val delaysMs: LongArray = longArrayOf(300, 600, 1000, 1500, 2000, 3000),
    private val totalBudgetMs: Long = 20_000L,
) {
    /**
     * The wait before attempt number [attempt] (1-based, so the first retry is 1), or
     * null when the budget is spent and the caller should give up.
     */
    fun delayBefore(attempt: Int, elapsedMs: Long): Long? {
        if (attempt < 1) return 0L
        if (elapsedMs >= totalBudgetMs) return null
        val d = delaysMs[(attempt - 1).coerceAtMost(delaysMs.size - 1)]
        return if (elapsedMs + d > totalBudgetMs) null else d
    }
}

object OwnerAddress {
    /** The framework's fixed group-owner address, used when it reports none. */
    const val FALLBACK = "192.168.49.1"

    /**
     * The address to connect to. Prefer what the framework reported for this group, but
     * only if it is a plain dotted IPv4; anything else (null, blank, IPv6, junk) falls
     * back to the fixed address so a malformed callback cannot send the joiner nowhere.
     */
    fun resolve(reported: String?): String {
        val r = reported?.trim().orEmpty().removePrefix("/")
        val parts = r.split('.')
        val ok = parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
        }
        return if (ok) r else FALLBACK
    }
}
