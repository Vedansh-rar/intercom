package com.intercom.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    private val plain = LogRedactor()

    @Test fun keyedPositionsAreRemoved() {
        assertEquals("fix lat=[redacted] lon=[redacted] acc=5", plain.redactLine("fix lat=12.971598 lon=77.594566 acc=5"))
        assertEquals("latE7: [redacted]", plain.redactLine("latE7: 285000000"))
    }

    @Test fun aPairOfCoordinatesIsRemovedWhateverTheKey() {
        assertEquals("own fix [position] acc=5", plain.redactLine("own fix 12.971598, 77.594566 acc=5"))
        assertEquals("[position]", plain.redactLine("-33.865143/151.209900"))
    }

    @Test fun aLoneLongDecimalIsTreatedAsACoordinate() {
        assertEquals("pos [coord]", plain.redactLine("pos 12.97159"))
        // Statistics and timestamps keep their short decimals.
        assertEquals("loss=3.5% at 18:39:01.123", plain.redactLine("loss=3.5% at 18:39:01.123"))
        // Too big to be a degree.
        assertEquals("size 1234.56789", plain.redactLine("size 1234.56789"))
    }

    @Test fun theRideCodeIsRemovedWhateverSeparatorsItIsShownWith() {
        val r = LogRedactor(secrets = listOf("river amber tiger window pearl"))
        assertEquals("code [ride-code] joined", r.redactLine("code river-amber-tiger-window-pearl joined"))
        assertEquals("[ride-code]", r.redactLine("River Amber Tiger Window Pearl"))
        assertEquals("[ride-code]", r.redactLine("river.amber.tiger.window.pearl"))
    }

    @Test fun passphraseAndKeyValuesAreRemoved() {
        assertEquals("passphrase=[redacted] ok", plain.redactLine("passphrase=hunter2 ok"))
        assertEquals("ride code: [redacted]", plain.redactLine("ride code: \"one two three\""))
        assertEquals("psk=[redacted]", plain.redactLine("psk=ABCDEF"))
    }

    @Test fun namesAreRemovedAsWholeWordsOnly() {
        val r = LogRedactor(names = listOf("Rahul", "Ji"))
        assertEquals("Connected to [name]", r.redactLine("Connected to Rahul"))
        assertEquals("Connected to [name]!", r.redactLine("Connected to rahul!"))
        assertEquals("Rahulji stays", r.redactLine("Rahulji stays"))
    }

    @Test fun veryShortNamesAreIgnored() {
        val r = LogRedactor(names = listOf("A"))
        assertEquals("A call", r.redactLine("A call"))
    }

    @Test fun bluetoothAddressesKeepOnlyTheVendorPart() {
        assertEquals("peer AA:BB:CC:XX:XX:XX up", plain.redactLine("peer AA:BB:CC:11:22:33 up"))
    }

    @Test fun keyLengthTokensAreRemoved() {
        assertEquals("blob [token] end", plain.redactLine("blob 3q2+7wEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA== end"))
        assertEquals("id [token]", plain.redactLine("id 0123456789abcdef0123456789abcdef"))
    }

    @Test fun longIdentifiersWithoutDigitsAreNotTokens() {
        val line = "CallControllerStartedSomethingVeryLongIndeedYes"
        assertEquals(line, plain.redactLine(line))
    }

    @Test fun ordinaryDiagnosticsSurvive() {
        val lines = listOf(
            "I/RfcommLink: Retrying in 4s (attempt 2)",
            "I/IntercomStats: codec=Opus@24000 io=oboe/oboe rtt=45 loss=0.0",
            "W/MicCapture: reopen attempt 3 failed, code 5",
            "I/CallController: SOS triggered, position=true".replace("position=true", "with a position"),
            "keyCode=79 down",
        )
        for (l in lines) assertEquals(l, plain.redactLine(l))
    }

    @Test fun redactHandlesManyLines() {
        val out = plain.redact("a lat=1.23456\nb\nc 12.97159, 77.59456")
        assertEquals(listOf("a lat=[redacted]", "b", "c [position]"), out.lines())
    }

    @Test fun nothingSensitiveSurvivesAMixedLine() {
        val r = LogRedactor(names = listOf("Asha"), secrets = listOf("river amber tiger window pearl"))
        val out = r.redactLine("Asha at 12.971598,77.594566 via AA:BB:CC:11:22:33 code river amber tiger window pearl")
        assertFalse(out.contains("Asha"))
        assertFalse(out.contains("12.97"))
        assertFalse(out.contains("11:22:33"))
        assertFalse(out.contains("river"))
        assertTrue(out.contains("[name]"))
    }
}
