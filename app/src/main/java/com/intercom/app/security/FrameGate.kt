package com.intercom.app.security

import com.intercom.app.protocol.Message

/**
 * The receive-side gate: what happens to a frame, given how the channel is protected.
 *
 * Pure, so it is unit-tested. [CallController] runs every non-handshake frame through it
 * before anything else looks at the payload.
 */
object FrameGate {

    sealed class Result {
        /** Act on this. For an encrypted channel the payload is decrypted and the flags cleaned. */
        class Accept(val message: Message) : Result()

        /** A cleartext frame in an encrypted call (or the reverse): never legitimate. */
        object WrongForm : Result()

        /** Failed the tag, replayed, or malformed. */
        object Invalid : Result()

        /** The security check has not finished: no media or control traffic yet. */
        object NotReady : Result()
    }

    fun admit(security: ChannelSecurity, cipher: FrameCipher?, m: Message): Result = when (security) {
        ChannelSecurity.Encrypted -> when {
            cipher == null -> Result.NotReady
            !FrameCipher.isEncrypted(m.flags) -> Result.WrongForm
            else -> cipher.open(m.type, m.seq, m.flags, m.payload)?.let {
                Result.Accept(Message(m.type, m.seq, FrameCipher.clearEncryptedBit(m.flags), it))
            } ?: Result.Invalid
        }
        // We are not decrypting, so a frame marked encrypted could only be misread as audio.
        ChannelSecurity.Unencrypted ->
            if (FrameCipher.isEncrypted(m.flags)) Result.WrongForm else Result.Accept(m)
        ChannelSecurity.Pending, ChannelSecurity.Authenticating -> Result.NotReady
    }
}
