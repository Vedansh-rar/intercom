package com.intercom.app.i18n

import com.intercom.app.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test fun unknownOrMissingNamesFollowThePhone() {
        assertEquals(AppLanguage.System, AppLanguage.parse(null))
        assertEquals(AppLanguage.System, AppLanguage.parse(""))
        assertEquals(AppLanguage.System, AppLanguage.parse("Klingon"))
    }

    @Test fun storedNamesRoundTrip() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.parse(l.name))
    }

    @Test fun tags() {
        assertNull(AppLanguage.System.tag)
        assertEquals("en", AppLanguage.English.tag)
        assertEquals("hi", AppLanguage.Hindi.tag)
        assertEquals(listOf("en", "hi"), AppLanguage.supportedTags)
    }
}
