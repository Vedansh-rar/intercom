package com.intercom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(s: String) = SemVer.parse(s)!!

    @Test fun parses_common_tag_shapes() {
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 0), v("V1.2"))
        assertEquals(SemVer(4, 0, 0), v("4"))
        assertEquals(listOf("phase1"), v("1.0-phase1").pre)
        assertEquals(listOf("rc", "1"), v("2.0.0-rc.1+build.5").pre)
        assertEquals(SemVer(2, 0, 0), v("2.0.0+build.5"))
    }

    @Test fun rejects_things_that_are_not_versions() {
        for (bad in listOf("", " ", "nightly", "latest", "1.2.3.4", "1..2", "a.b.c", "1.2.x", "v", "-1.0",
            "1.0-", "1.0-rc..1", "1.0-01", "1.0+", "1.0+a..b", "1234567890.0.0", "1.0 beta")) {
            assertNull("'$bad' should not parse", SemVer.parse(bad))
        }
        assertNull(SemVer.parse(null))
    }

    @Test fun trims_whitespace() {
        assertNotNull(SemVer.parse(" v1.0.0\n"))
    }

    @Test fun orders_core_numbers_numerically_not_as_text() {
        assertTrue(v("1.10.0").isNewerThan(v("1.9.0")))
        assertTrue(v("2.0.0").isNewerThan(v("1.99.99")))
        assertTrue(v("1.0.10").isNewerThan(v("1.0.9")))
    }

    @Test fun a_release_is_newer_than_its_own_prereleases() {
        assertTrue(v("1.0.0").isNewerThan(v("1.0.0-rc.1")))
        assertFalse(v("1.0.0-rc.1").isNewerThan(v("1.0.0")))
        // The app's own build label: the real 1.0.0 must be offered over "1.0-phase1".
        assertTrue(v("1.0.0").isNewerThan(v("1.0-phase1")))
        assertTrue(v("1.0.1").isNewerThan(v("1.0-phase1")))
    }

    @Test fun prerelease_ordering_follows_semver_org() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        ).map { v(it) }
        for (i in 0 until ordered.size - 1) {
            assertTrue("${ordered[i]} < ${ordered[i + 1]}", ordered[i + 1].isNewerThan(ordered[i]))
            assertFalse(ordered[i].isNewerThan(ordered[i + 1]))
        }
    }

    @Test fun build_metadata_is_ignored() {
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
        assertFalse(v("1.0.0+b").isNewerThan(v("1.0.0+a")))
    }

    @Test fun equal_or_older_is_never_newer_so_no_downgrade() {
        assertFalse(v("1.2.3").isNewerThan(v("1.2.3")))
        assertFalse(v("1.2.3").isNewerThan(v("1.2.4")))
        assertFalse(v("0.9").isNewerThan(v("1.0")))
        assertFalse(v("v1.0").isNewerThan(v("1.0.0")))
    }

    @Test fun missing_parts_count_as_zero() {
        assertEquals(0, v("1").compareTo(v("1.0.0")))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    @Test fun to_string_is_canonical() {
        assertEquals("1.2.0", v("v1.2").toString())
        assertEquals("1.0.0-phase1", v("1.0-phase1").toString())
        assertEquals("2.0.0-rc.1", v("2.0.0-rc.1+x").toString())
    }
}
