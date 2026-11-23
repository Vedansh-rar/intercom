package com.intercom.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogReportTest {
    private val facts = LogReport.Facts(
        appVersion = "1.0 (1)", androidRelease = "14", sdk = 34, device = "Acme Phone",
        generatedAtMs = 1234L, settings = listOf("Transport" to "Auto"),
    )

    @Test fun headerSaysWhatIsAndIsNotInside() {
        val text = LogReport.build(facts, "line one", LogRedactor())
        assertTrue(text.contains("App: 1.0 (1)"))
        assertTrue(text.contains("Device: Acme Phone"))
        assertTrue(text.contains("Transport: Auto"))
        assertTrue(text.contains("Nothing was uploaded"))
        assertTrue(text.endsWith("line one\n"))
    }

    @Test fun everyBodyLineIsRedacted() {
        val text = LogReport.build(facts, "a lat=1.23456\nb code=xyz", LogRedactor())
        assertFalse(text.contains("1.23456"))
        assertFalse(text.contains("xyz"))
    }

    @Test fun anOverlongLogKeepsTheNewestLinesAndSaysSo() {
        val raw = (1..10).joinToString("\n") { "line $it" }
        val text = LogReport.build(facts, raw, LogRedactor(), maxLines = 4)
        assertTrue(text.contains("older 6 dropped"))
        val body = text.substringAfter("----\n").trim().lines()
        assertEquals(listOf("line 7", "line 8", "line 9", "line 10"), body)
    }

    @Test fun blankLinesAreDropped() {
        val text = LogReport.build(facts, "a\n\n\nb", LogRedactor())
        assertEquals(listOf("a", "b"), text.substringAfter("----\n").trim().lines())
    }
}
