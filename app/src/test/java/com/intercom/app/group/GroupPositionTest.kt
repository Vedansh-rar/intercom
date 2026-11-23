package com.intercom.app.group

import com.intercom.app.location.OwnFix
import com.intercom.app.location.Side
import com.intercom.app.protocol.PositionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Positions travelling through a real (simulated) mesh: relayed, tracked, forgotten. */
class GroupPositionTest {

    private val lat0 = 12.0
    private val lon0 = 77.0
    private val mPerDeg = 111_195.0

    private fun line(vararg labels: String): SimNet {
        val net = SimNet()
        labels.forEachIndexed { i, l -> net.add(l, 2000 + i) }
        labels.toList().zipWithNext().forEach { (a, b) -> net.link(a, b) }
        // everyone introduces themselves so the peer table has a row for each rider
        net.nodes.values.forEach { it.node.beacon() }
        net.pump()
        return net
    }

    private fun SimNet.n(l: String) = nodes.getValue(l)

    private fun report(northM: Double, acc: Int = 5, speedCms: Int = 0) =
        PositionReport(((lat0 + northM / mPerDeg) * 1e7).toInt(), (lon0 * 1e7).toInt(), speedCms, 0, acc, 0L)

    private fun own(northM: Double, heading: Double? = null, speed: Double = 0.0) =
        OwnFix(lat0 + northM / mPerDeg, lon0, speed, heading, 5.0, 0L)

    private fun GroupNode.distanceTo(id: Int) = snapshot().single { it.id == id }.distance

    @Test fun aPositionIsRelayedAndShownAsADistance() {
        val net = line("A", "B", "C")
        net.n("A").node.positions.setOwn(own(0.0))
        net.n("C").node.positions.setOwn(own(500.0))

        assertTrue(net.n("C").node.sendPosition(report(500.0)))
        net.pump()

        // A is two hops from C and still learns where C is
        val d = net.n("A").node.distanceTo(net.n("C").id)
        assertNotNull(d)
        assertEquals(500.0, d!!.meters, 1.0)
        assertEquals(0.0, d.bearingDeg, 0.5)       // C is due north of A
        // B, in the middle, has not set its own position, so it cannot show a distance
        assertNull(net.n("B").node.distanceTo(net.n("C").id))
    }

    @Test fun everyRiderGetsADistanceToEveryoneWhoShares() {
        val net = line("A", "B", "C")
        val at = mapOf("A" to 0.0, "B" to 200.0, "C" to 700.0)
        for ((l, m) in at) net.n(l).node.positions.setOwn(own(m))
        for ((l, m) in at) net.n(l).node.sendPosition(report(m))
        net.pump()

        for (from in at.keys) for (to in at.keys) {
            if (from == to) continue
            val d = net.n(from).node.distanceTo(net.n(to).id)!!
            assertEquals("$from -> $to", Math.abs(at.getValue(from) - at.getValue(to)), d.meters, 1.5)
        }
    }

    @Test fun aheadAndBehindInAConvoy() {
        val net = line("A", "B")
        // A rides north at speed; B is 300 m north of A
        net.n("A").node.positions.setOwn(own(0.0, heading = 0.0, speed = 10.0))
        net.n("B").node.sendPosition(report(300.0, speedCms = 1000))
        net.pump()
        assertEquals(Side.Ahead, net.n("A").node.distanceTo(net.n("B").id)!!.side)
    }

    @Test fun aMutedRiderIsStillTracked() {
        val net = line("A", "B")
        net.n("A").node.positions.setOwn(own(0.0))
        net.n("A").node.setPeerMuted(net.n("B").id, true)
        net.n("B").node.sendPosition(report(250.0))
        net.pump()
        assertEquals(250.0, net.n("A").node.distanceTo(net.n("B").id)!!.meters, 1.0)
    }

    @Test fun anUnusablePositionIsIgnored() {
        val net = line("A", "B")
        net.n("A").node.positions.setOwn(own(0.0))
        net.n("B").node.sendPosition(report(250.0, acc = 900))
        net.n("B").node.sendPosition(PositionReport(990_000_000, 0, 0, 0, 5, 0L))
        net.pump()
        assertNull(net.n("A").node.distanceTo(net.n("B").id))
    }

    @Test fun aTruncatedPositionPayloadIsIgnored() {
        val net = line("A", "B")
        net.n("A").node.positions.setOwn(own(0.0))
        // a v-next sender with a shorter body: parse refuses it, nothing is stored
        assertNull(PositionReport.parse(ByteArray(10)))
        net.n("B").node.sendPosition(report(100.0))
        net.pump()
        assertNotNull(net.n("A").node.distanceTo(net.n("B").id))
    }

    @Test fun aRiderWhoLeavesTheMeshLosesTheirPosition() {
        val net = line("A", "B", "C")
        net.n("A").node.positions.setOwn(own(0.0))
        net.n("C").node.sendPosition(report(500.0))
        net.n("B").node.sendPosition(report(100.0))
        net.pump()
        assertNotNull(net.n("A").node.distanceTo(net.n("C").id))

        net.cut("A", "B")                          // A loses the only route to B and C
        assertTrue(net.n("A").node.snapshot().isEmpty())
        assertTrue(net.n("A").node.positions.snapshot().isEmpty())
    }

    @Test fun aPositionNeverEntersTheAudioPath() {
        val net = line("A", "B")
        net.n("B").node.sendPosition(report(100.0))
        net.pump()
        assertNull(net.n("A").hear())
        assertEquals(0, net.n("A").mixer.streamCount)
        assertNull(net.n("A").node.floor.holderId())
    }

    @Test fun reportsAreFloodBounded() {
        val net = SimNet()
        val labels = listOf("A", "B", "C", "D")
        labels.forEachIndexed { i, l -> net.add(l, 3000 + i) }
        for (i in labels.indices) for (j in i + 1 until labels.size) net.link(labels[i], labels[j])
        val before = net.steps
        net.n("A").node.sendPosition(report(10.0))
        net.pump()
        // 3 neighbours get it once; each of them relays it at most to the others, then dedup
        assertTrue("took ${net.steps - before} deliveries", net.steps - before <= 3 * 3)
    }
}
