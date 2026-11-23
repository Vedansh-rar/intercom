package com.intercom.app.protocol

/**
 * Optional features, negotiated at HELLO.
 *
 * Each side advertises what it can do; a call uses the **intersection**. A v1 peer has no
 * capability field at all, which is read as "none", so a call with a v1 buddy is exactly
 * the v1 call: ADPCM audio, no position, no authentication, no group.
 */
object Capabilities {
    const val OPUS = 1 shl 0
    const val POSITION = 1 shl 1
    const val AUTH = 1 shl 2
    const val GROUP = 1 shl 3

    /** Every bit this build knows about; unknown bits from a newer peer are ignored. */
    const val KNOWN = OPUS or POSITION or AUTH or GROUP

    /**
     * What this build offers, given what this phone can actually run. A bit is only ever
     * set for a feature that is implemented *and* usable here: advertising a capability
     * this build cannot honour would make the buddy rely on it. Position and group join
     * this as their phases land.
     *
     * [authAvailable] is true when a ride code is set: without one this phone cannot prove
     * anything, so it must not claim [AUTH].
     */
    fun local(opusAvailable: Boolean, authAvailable: Boolean = false): Int =
        (if (opusAvailable) OPUS else 0) or (if (authAvailable) AUTH else 0)

    fun names(caps: Int): List<String> = buildList {
        if (caps and OPUS != 0) add("OPUS")
        if (caps and POSITION != 0) add("POSITION")
        if (caps and AUTH != 0) add("AUTH")
        if (caps and GROUP != 0) add("GROUP")
    }
}

/** The audio codec a call uses. Values match [Hello.codec]. */
enum class CodecId(val wireValue: Int) {
    Adpcm(Hello.CODEC_ADPCM),
    Opus(Hello.CODEC_OPUS),
}

/** What a call may use, decided once from both sides' HELLOs. */
data class NegotiatedFeatures(
    val peerVersion: Int,
    val codec: CodecId,
    val position: Boolean,
    val auth: Boolean,
    val group: Boolean,
) {
    /** True when the buddy is a v1 build: nothing beyond the original call. */
    val isLegacyPeer: Boolean get() = peerVersion < 2

    companion object {
        /**
         * Intersection of what we offer and what the peer offered.
         *
         * Opus is used only if both sides have [Capabilities.OPUS]; otherwise ADPCM, which
         * every build speaks. Unknown capability bits are masked off.
         */
        fun negotiate(localCaps: Int, peer: Hello): NegotiatedFeatures {
            val common = if (peer.version < 2) 0 else
                (localCaps and peer.capabilities) and Capabilities.KNOWN
            return NegotiatedFeatures(
                peerVersion = peer.version,
                codec = if (common and Capabilities.OPUS != 0) CodecId.Opus else CodecId.Adpcm,
                position = common and Capabilities.POSITION != 0,
                auth = common and Capabilities.AUTH != 0,
                group = common and Capabilities.GROUP != 0,
            )
        }

        /** What a call looks like before any HELLO has arrived: the v1 call. */
        val LEGACY = NegotiatedFeatures(1, CodecId.Adpcm, position = false, auth = false, group = false)
    }
}
