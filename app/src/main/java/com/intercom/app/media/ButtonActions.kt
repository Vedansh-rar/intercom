package com.intercom.app.media

/** What a headset or remote button gesture can be set to do. */
enum class ButtonAction(val label: String) {
    None("Nothing (leave it to the music app)"),
    PushToTalk("Push to talk on/off"),
    Mute("Mute / unmute my mic"),
    Deafen("Deafen / undeafen"),
    CycleNoise("Change noise reduction"),
    EndCall("End the call");

    /** The next one in the list, wrapping round: how the settings row cycles through them. */
    fun next(): ButtonAction = entries[(ordinal + 1) % entries.size]
}

/** Which gesture of which button. Volume keys only reach the app when the opt-in is on. */
enum class ButtonInput(val label: String) {
    Single("Single press"),
    Double("Double press"),
    Triple("Triple press"),
    Long("Long press"),
    VolumeUp("Volume up"),
    VolumeDown("Volume down"),
}

/**
 * What each button input does. The defaults keep the long-standing behaviour (double press is
 * push-to-talk, long press ends the call, a single press stays with the music app) and put
 * mute on the triple press, where ending the call by accident used to be.
 */
data class ButtonMapping(
    val single: ButtonAction = ButtonAction.None,
    val double: ButtonAction = ButtonAction.PushToTalk,
    val triple: ButtonAction = ButtonAction.Mute,
    val long: ButtonAction = ButtonAction.EndCall,
    val volumeUp: ButtonAction = ButtonAction.PushToTalk,
    val volumeDown: ButtonAction = ButtonAction.Mute,
) {
    fun actionFor(input: ButtonInput): ButtonAction = when (input) {
        ButtonInput.Single -> single
        ButtonInput.Double -> double
        ButtonInput.Triple -> triple
        ButtonInput.Long -> long
        ButtonInput.VolumeUp -> volumeUp
        ButtonInput.VolumeDown -> volumeDown
    }

    fun with(input: ButtonInput, action: ButtonAction): ButtonMapping = when (input) {
        ButtonInput.Single -> copy(single = action)
        ButtonInput.Double -> copy(double = action)
        ButtonInput.Triple -> copy(triple = action)
        ButtonInput.Long -> copy(long = action)
        ButtonInput.VolumeUp -> copy(volumeUp = action)
        ButtonInput.VolumeDown -> copy(volumeDown = action)
    }

    fun forGesture(g: ButtonGesture): ButtonAction = when (g) {
        ButtonGesture.Single -> single
        ButtonGesture.Double -> double
        ButtonGesture.Triple -> triple
        ButtonGesture.LongPress -> long
    }
}
