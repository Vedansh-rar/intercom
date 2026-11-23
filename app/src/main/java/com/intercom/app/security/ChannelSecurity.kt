package com.intercom.app.security

/**
 * Whether the voice channel to the buddy is protected.
 *
 * [Pending] is "no buddy connected yet", [Authenticating] is the short challenge-response
 * after connect, [Encrypted] is the normal state, and [Unencrypted] only happens when the
 * rider has allowed a legacy buddy, in which case the call screen warns about it.
 */
enum class ChannelSecurity { Pending, Authenticating, Encrypted, Unencrypted }
