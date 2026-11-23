package com.intercom.app.util

/**
 * Walkie-talkie mode is a preset on the over-the-air group ride, not a new radio: nearby
 * phones join through Nearby Connections with no pairing, talk is push-to-talk only, and the
 * end of every transmission is marked with a short "roger" beep. These are the rules that
 * make it that, kept here so they can be tested without a phone.
 */
object WalkiePolicy {
    /** Walkie is always push-to-talk: an open mic on a loudspeaker would feed back. */
    fun talkMode(walkie: Boolean, chosen: TalkMode): TalkMode = if (walkie) TalkMode.PushToTalk else chosen

    /** Walkie is a group of phones close by; the group switch is implied. */
    fun isGroup(walkie: Boolean, groupRide: Boolean): Boolean = walkie || groupRide

    /** Walkie is for very short distances over the radios; it never goes through a relay. */
    fun useInternet(walkie: Boolean, internetRide: Boolean): Boolean = internetRide && !walkie

    /** The roger beep: always in walkie mode, otherwise only if the rider wants "muted" beeps. */
    fun beepWhenChannelFree(walkie: Boolean, beepWhenMuted: Boolean): Boolean = walkie || beepWhenMuted

    /** Transmissions can follow each other quickly, so the roger beep skips the usual 2 s gap. */
    fun beepIgnoresGap(walkie: Boolean): Boolean = walkie
}
