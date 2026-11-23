package com.intercom.app.security

/**
 * What to do with a freshly connected buddy, given what each side can do.
 *
 * A peer that cannot authenticate - a version 1 build, or a version 2 build from before this
 * feature - is refused unless the rider has explicitly allowed an unencrypted buddy.
 */
object SecurityPolicy {

    enum class Decision {
        /** Run the challenge-response, then encrypt. */
        Authenticate,

        /** Carry on without protection, because the rider allowed it. The UI must warn. */
        ProceedUnencrypted,

        /** Close the link and say why. */
        RefuseBuddy,
    }

    fun decide(hasLocalCode: Boolean, peerSupportsAuth: Boolean, allowLegacy: Boolean): Decision = when {
        hasLocalCode && peerSupportsAuth -> Decision.Authenticate
        allowLegacy -> Decision.ProceedUnencrypted
        else -> Decision.RefuseBuddy
    }
}
