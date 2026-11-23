package com.intercom.app.selftest

import android.media.AudioDeviceInfo

/** What the phone is using to hear the rider and to speak to them, in words a rider can act on. */
enum class RouteKind {
    BluetoothCall,      // headset over SCO / HFP: mic works, music is mono
    BluetoothMusic,     // A2DP only: no headset mic
    BluetoothLe,        // LE Audio: mic and hi-fi together
    Wired,
    Usb,
    Phone,              // the phone's own mic and speaker or earpiece
    None,
}

data class RouteReport(
    val input: RouteKind,
    val output: RouteKind,
    /** A headset is connected for music but the call is not using its microphone. */
    val headsetMicNotUsed: Boolean,
    /** Sound would come out of the phone's loudspeaker. */
    val outputIsLoudspeaker: Boolean,
    /** Nothing to hear anyone through. */
    val noOutput: Boolean,
)

object AudioRoutes {

    fun kindOf(type: Int): RouteKind = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RouteKind.BluetoothCall
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> RouteKind.BluetoothMusic
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> RouteKind.BluetoothLe
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> RouteKind.Wired
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteKind.Usb
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.Phone
        else -> RouteKind.None
    }

    /**
     * [inputTypes] and [outputTypes] are the `AudioDeviceInfo.getType()` values of what is
     * connected right now. A headset beats the phone, and a call-capable connection beats a
     * music-only one, because that is the order the intercom would pick them in.
     */
    fun report(inputTypes: List<Int>, outputTypes: List<Int>): RouteReport {
        val ins = inputTypes.map(::kindOf)
        val outs = outputTypes.map(::kindOf)
        val input = best(ins, listOf(RouteKind.BluetoothLe, RouteKind.Usb, RouteKind.Wired, RouteKind.BluetoothCall, RouteKind.Phone))
        val output = best(outs, listOf(RouteKind.BluetoothLe, RouteKind.Usb, RouteKind.Wired, RouteKind.BluetoothMusic, RouteKind.BluetoothCall, RouteKind.Phone))
        val hasBtOutput = outs.any { it == RouteKind.BluetoothMusic || it == RouteKind.BluetoothCall || it == RouteKind.BluetoothLe }
        val micIsHeadset = input == RouteKind.BluetoothCall || input == RouteKind.BluetoothLe ||
            input == RouteKind.Wired || input == RouteKind.Usb
        val speaker = outputTypes.any { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val headsetOut = outs.any { it != RouteKind.Phone && it != RouteKind.None }
        return RouteReport(
            input = input,
            output = output,
            headsetMicNotUsed = hasBtOutput && !micIsHeadset,
            outputIsLoudspeaker = speaker && !headsetOut,
            noOutput = outs.none { it != RouteKind.None },
        )
    }

    private fun best(found: List<RouteKind>, order: List<RouteKind>): RouteKind =
        order.firstOrNull { it in found } ?: RouteKind.None
}
