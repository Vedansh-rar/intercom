package com.intercom.app.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {
    private var t = 0L
    private fun table() = PeerTable(now = { t }, expiryMs = 15_000)
    private fun PeerTable.view(
        speaking: Set<Int> = emptySet(),
    ) = snapshot({ it in speaking }, { 0.0 }, { false })

    @Test fun aRiderAppearsWhenHeard() {
        val p = table()
        p.heard(1, 1, "ep1")
        assertTrue(p.contains(1))
        assertEquals(1, p.size)
        assertEquals(PeerTable.DEFAULT_NAME, p.nameOf(1))
    }

    @Test fun infoSetsNameAndPriorityOnlyForKnownRiders() {
        val p = table()
        p.info(1, "Ghost", 1)
        assertFalse(p.contains(1))
        p.heard(1, 1, "ep1")
        p.info(1, "Asha", 1)
        assertEquals("Asha", p.nameOf(1))
        assertEquals(1, p.priorityOf(1))
    }

    @Test fun aBlankNameFallsBackToTheDefault() {
        val p = table()
        p.heard(1, 1, "ep")
        p.info(1, "  ", 0)
        assertEquals(PeerTable.DEFAULT_NAME, p.nameOf(1))
    }

    @Test fun theShortestRouteIsKept() {
        val p = table()
        p.heard(1, 2, "viaB")
        p.heard(1, 1, "direct")
        assertEquals(1, p.view().single().hops)
        p.heard(1, 3, "viaC") // longer route while the short one is fresh
        assertEquals(1, p.view().single().hops)
    }

    @Test fun aLongerRouteTakesOverOnceTheShortOneIsQuiet() {
        val p = table()
        p.heard(1, 1, "direct")
        t += PeerTable.ROUTE_SWITCH_MS + 1
        p.heard(1, 2, "viaB")
        assertEquals(2, p.view().single().hops)
    }

    @Test fun silentRidersExpire() {
        val p = table()
        p.heard(1, 1, "a")
        t = 10_000
        p.heard(2, 1, "b")
        t = 16_000
        assertEquals(listOf(1), p.expire())
        assertFalse(p.contains(1))
        assertTrue(p.contains(2))
    }

    @Test fun hearingARiderRefreshesThem() {
        val p = table()
        p.heard(1, 1, "a")
        t = 14_000
        p.heard(1, 1, "a")
        t = 28_000
        assertTrue(p.expire().isEmpty())
    }

    @Test fun aDroppedConnectionRemovesEveryoneBehindIt() {
        val p = table()
        p.heard(1, 1, "epB")
        p.heard(2, 2, "epB")
        p.heard(3, 1, "epC")
        assertEquals(setOf(1, 2), p.removeVia("epB").toSet())
        assertTrue(p.contains(3))
        assertEquals(1, p.size)
    }

    @Test fun removingAnUnknownConnectionIsHarmless() {
        assertTrue(table().removeVia("nothing").isEmpty())
    }

    @Test fun muteSurvivesAnExpiryAndRejoin() {
        val p = table()
        p.heard(1, 1, "a")
        p.setMuted(1, true)
        t = 20_000
        p.expire()
        p.heard(1, 1, "a")
        assertTrue(p.view().single().muted)
        p.setMuted(1, false)
        assertFalse(p.view().single().muted)
    }

    @Test fun theListShowsHostsFirstThenByName() {
        val p = table()
        p.heard(1, 1, "a"); p.info(1, "Zed", 0)
        p.heard(2, 1, "b"); p.info(2, "Amy", 0)
        p.heard(3, 1, "c"); p.info(3, "Midge", 1)
        assertEquals(listOf("Midge", "Amy", "Zed"), p.view().map { it.name })
        assertTrue(p.view().first().isHost)
    }

    @Test fun speakingComesFromTheMixer() {
        val p = table()
        p.heard(1, 1, "a"); p.heard(2, 1, "b")
        val v = p.view(speaking = setOf(2)).associateBy { it.id }
        assertFalse(v.getValue(1).speaking)
        assertTrue(v.getValue(2).speaking)
    }

    // ---- quality ----------------------------------------------------------------

    @Test fun lowLossOnADirectLinkIsGood() {
        assertEquals(LinkQuality.Good, PeerTable.qualityOf(1, 1_000, true, 0.5))
    }

    @Test fun modestLossOrARelayIsFair() {
        assertEquals(LinkQuality.Fair, PeerTable.qualityOf(1, 1_000, true, 5.0))
        assertEquals(LinkQuality.Fair, PeerTable.qualityOf(2, 1_000, true, 0.0))
    }

    @Test fun highLossIsPoor() {
        assertEquals(LinkQuality.Poor, PeerTable.qualityOf(1, 1_000, true, 12.0))
    }

    @Test fun withNoAudioYetRecencyOfBeaconsDecides() {
        assertEquals(LinkQuality.Good, PeerTable.qualityOf(1, 2_000, false, 0.0))
        assertEquals(LinkQuality.Fair, PeerTable.qualityOf(2, 2_000, false, 0.0))
        assertEquals(LinkQuality.Poor, PeerTable.qualityOf(1, 8_000, false, 0.0))
    }

    @Test fun aRiderNotHeardForTenSecondsIsPoorWhateverElse() {
        assertEquals(LinkQuality.Poor, PeerTable.qualityOf(1, 11_000, true, 0.0))
    }
}
