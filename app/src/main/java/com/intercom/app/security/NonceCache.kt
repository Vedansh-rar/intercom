package com.intercom.app.security

/**
 * Remembers the challenge nonces recently seen from peers, so a nonce is never accepted
 * twice across connections.
 *
 * A replayed challenge could not produce a valid response anyway (the MAC covers *our*
 * fresh nonce), so this is defence in depth against a broken random generator on the other
 * phone. Bounded, so it cannot grow without limit.
 */
class NonceCache(private val capacity: Int = 128) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) =
            size > capacity
    }

    /** Records [nonce]; false if it had been seen before. */
    @Synchronized
    fun add(nonce: ByteArray): Boolean = seen.put(key(nonce), Unit) == null

    @Synchronized
    fun contains(nonce: ByteArray): Boolean = seen.containsKey(key(nonce))

    @Synchronized
    fun clear() = seen.clear()

    private fun key(nonce: ByteArray) = nonce.joinToString("") { "%02x".format(it) }
}
