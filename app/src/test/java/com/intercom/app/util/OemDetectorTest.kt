package com.intercom.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemDetectorTest {

    @Test fun known_vendors_get_their_own_steps() {
        val cases = mapOf(
            "xiaomi" to "Xiaomi / Redmi / POCO",
            "redmi" to "Xiaomi / Redmi / POCO",
            "poco" to "Xiaomi / Redmi / POCO",
            "oppo" to "OPPO / realme",
            "realme" to "OPPO / realme",
            "vivo" to "vivo / iQOO",
            "iqoo" to "vivo / iQOO",
            "oneplus" to "OnePlus",
            "samsung" to "Samsung",
            "huawei" to "Huawei / Honor",
            "honor" to "Huawei / Honor",
        )
        for ((maker, vendor) in cases) {
            val g = OemDetector.guidanceFor(maker)
            assertNotNull(maker, g)
            assertEquals(maker, vendor, g!!.vendor)
            assertTrue("$maker has no steps", g.steps.isNotEmpty())
        }
    }

    @Test fun phones_without_an_aggressive_manager_get_no_vendor_page() {
        assertNull(OemDetector.guidanceFor("google"))
        assertNull(OemDetector.guidanceFor("motorola"))
        assertNull(OemDetector.guidanceFor(""))
    }

    @Test fun every_step_is_a_distinct_string_resource() {
        val all = listOf("xiaomi", "oppo", "vivo", "oneplus", "samsung", "huawei")
            .flatMap { OemDetector.guidanceFor(it)!!.steps }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it != 0 })
    }
}
