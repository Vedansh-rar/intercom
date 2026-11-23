package com.intercom.app.group

import com.intercom.app.audio.AudioConstants
import com.intercom.app.protocol.Message

/**
 * A convoy in memory: real [GroupNode]s, real mixers and floors, joined by a queue instead
 * of radios. A message sent to a neighbour is delivered on [pump], as that neighbour's
 * [GroupNode.onFrame] with the sender's label as the arrival "endpoint", exactly as the
 * Nearby link would.
 */
class SimNet {
    var now = 0L
    val nodes = LinkedHashMap<String, SimNode>()
    var steps = 0
    private val queue = ArrayDeque<Triple<String, String, Message>>()

    fun add(label: String, id: Int, priority: Int = 0): SimNode =
        SimNode(this, label, id, priority).also { nodes[label] = it }

    fun link(a: String, b: String) {
        nodes.getValue(a).neighbours += b
        nodes.getValue(b).neighbours += a
    }

    /** Breaks a link and tells both ends, as a Nearby disconnect would. */
    fun cut(a: String, b: String) {
        nodes.getValue(a).neighbours -= b
        nodes.getValue(b).neighbours -= a
        nodes.getValue(a).node.onEndpointLost(b)
        nodes.getValue(b).node.onEndpointLost(a)
    }

    fun enqueue(to: String, from: String, m: Message) { queue.addLast(Triple(to, from, m)) }

    /** Delivers until nothing is in flight. A flood that never ends fails the test. */
    fun pump(limit: Int = 100_000) {
        var n = 0
        while (queue.isNotEmpty()) {
            check(++n <= limit) { "the flood did not terminate" }
            val (to, from, m) = queue.removeFirst()
            val target = nodes[to] ?: continue
            if (from !in target.neighbours) continue // the link was cut while in flight
            target.received++
            steps++
            target.node.onFrame(from, m, now)
        }
    }

    class SimNode(val net: SimNet, val label: String, val id: Int, priority: Int) : MeshPort {
        val neighbours = LinkedHashSet<String>()
        val codecs = mutableListOf<FakeCodec>()
        var received = 0
        var sent = 0

        val mixer: GroupMixer = GroupMixer(
            newDecoder = { flag -> FakeCodec(flag).also { codecs += it } },
            jitterTarget = 1,
            duckFactor = { node.floor.duckFactor(it) },
            now = { net.now },
        )
        val node: GroupNode = GroupNode(id, label, priority, this, mixer, PeerTable({ net.now }), { net.now })

        override fun readyEndpoints(): Collection<String> = neighbours.toList()

        override fun sendTo(endpoint: String, type: Byte, flags: Byte, payload: ByteArray): Boolean {
            if (endpoint !in neighbours) return false
            sent++
            net.enqueue(endpoint, label, Message(type, 0, flags, payload))
            return true
        }

        /** What this rider hears right now: one mixed frame's first sample, or null if silent. */
        fun hear(): Short? {
            val out = ShortArray(AudioConstants.FRAME_SAMPLES)
            return if (mixer.take(out)) out[0] else null
        }

        fun speak(value: Int): Boolean {
            node.localSpeaking()
            return node.sendAudio(0, byteArrayOf(value.toByte()))
        }
    }
}
