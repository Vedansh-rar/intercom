package com.intercom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHostsAndPolicyTest {

    @Test fun allows_github_over_https() {
        assertTrue(UpdateHosts.isAllowed("https://api.github.com/repos/Vedansh-rar/intercom/releases/latest"))
        assertTrue(UpdateHosts.isAllowed("https://github.com/Vedansh-rar/intercom/releases/download/v2/a.apk"))
        assertTrue(UpdateHosts.isAllowed("https://objects.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(UpdateHosts.isAllowed("https://release-assets.githubusercontent.com/x"))
        assertTrue(UpdateHosts.isAllowed("HTTPS://GitHub.com/x"))
        assertTrue(UpdateHosts.isAllowed("https://github.com:443/x"))
    }

    @Test fun refuses_plain_http_and_other_schemes() {
        assertFalse(UpdateHosts.isAllowed("http://github.com/x"))
        assertFalse(UpdateHosts.isAllowed("ftp://github.com/x"))
        assertFalse(UpdateHosts.isAllowed("file:///sdcard/a.apk"))
        assertFalse(UpdateHosts.isAllowed("//github.com/x"))
    }

    @Test fun refuses_lookalike_hosts() {
        assertFalse(UpdateHosts.isAllowed("https://github.com.evil.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://evilgithub.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://notgithubusercontent.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://githubusercontent.com.evil.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://evil.com/github.com"))
        assertFalse(UpdateHosts.isAllowed("https://evil.com/?u=https://github.com/"))
        assertFalse(UpdateHosts.isAllowed("https://raw.github.com/x"))
    }

    @Test fun refuses_credentials_odd_ports_and_junk() {
        assertFalse(UpdateHosts.isAllowed("https://github.com@evil.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://user:pw@github.com/x"))
        assertFalse(UpdateHosts.isAllowed("https://github.com:8443/x"))
        assertFalse(UpdateHosts.isAllowed(""))
        assertFalse(UpdateHosts.isAllowed("not a url"))
        assertFalse(UpdateHosts.isAllowed("https://"))
        assertFalse(UpdateHosts.isAllowed("https://git hub.com/x"))
    }

    // ---- UpdatePolicy

    private val day = UpdatePolicy.INTERVAL_MS

    @Test fun never_checks_unless_switched_on() {
        assertFalse(UpdatePolicy.shouldAutoCheck(false, 0, 10 * day, false))
        assertFalse(UpdatePolicy.shouldAutoCheck(false, 1, 100 * day, false))
    }

    @Test fun never_checks_during_a_call() {
        assertFalse(UpdatePolicy.shouldAutoCheck(true, 0, 10 * day, true))
    }

    @Test fun checks_once_a_day() {
        val now = 100 * day
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 0, now, false))
        assertFalse(UpdatePolicy.shouldAutoCheck(true, now - day + 1, now, false))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, now - day, now, false))
        assertTrue(UpdatePolicy.shouldAutoCheck(true, now - 3 * day, now, false))
    }

    @Test fun a_clock_set_back_counts_as_due() {
        assertTrue(UpdatePolicy.shouldAutoCheck(true, 50 * day, 10 * day, false))
    }

    @Test fun interval_is_24_hours() {
        assertEquals(86_400_000L, UpdatePolicy.INTERVAL_MS)
    }
}
