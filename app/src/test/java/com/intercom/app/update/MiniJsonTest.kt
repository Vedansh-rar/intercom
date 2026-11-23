package com.intercom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    private fun fails(text: String) {
        try {
            MiniJson.parse(text)
            throw AssertionError("expected a parse error for: $text")
        } catch (e: MiniJson.ParseError) {
            // expected
        }
    }

    @Test fun parses_scalars() {
        assertEquals("hi", MiniJson.parse("\"hi\""))
        assertEquals(42L, MiniJson.parse("42"))
        assertEquals(-7L, MiniJson.parse("-7"))
        assertEquals(1.5, MiniJson.parse("1.5"))
        assertEquals(1000.0, MiniJson.parse("1e3"))
        assertEquals(true, MiniJson.parse("true"))
        assertEquals(false, MiniJson.parse("false"))
        assertNull(MiniJson.parse("null"))
    }

    @Test fun parses_nested_documents() {
        val v = MiniJson.parse("""{"a":[1,2,{"b":null}],"c":"d", "e": {}}""")
        val o = MiniJson.obj(v)!!
        assertEquals(listOf(1L, 2L, mapOf("b" to null)), MiniJson.list(o["a"]))
        assertEquals("d", o["c"])
        assertEquals(emptyMap<String, Any?>(), MiniJson.obj(o["e"]))
    }

    @Test fun handles_string_escapes() {
        assertEquals("a\"b\\c/d\ne\tf", MiniJson.parse("\"a\\\"b\\\\c\\/d\\ne\\tf\""))
        assertEquals("é", MiniJson.parse("\"\\u00e9\""))
        assertEquals("😀", MiniJson.parse("\"\\ud83d\\ude00\""))
    }

    @Test fun tolerates_whitespace_around_tokens() {
        assertEquals(listOf(1L), MiniJson.parse(" \n[ 1 ]\t"))
    }

    @Test fun rejects_malformed_input() {
        fails("")
        fails("{")
        fails("[1,")
        fails("[1 2]")
        fails("{\"a\"}")
        fails("{\"a\":1,}")
        fails("{a:1}")
        fails("\"unterminated")
        fails("\"bad \\q escape\"")
        fails("\"bad \\u12 escape\"")
        fails("tru")
        fails("nul")
        fails("-")
        fails("1.2.3")
    }

    @Test fun rejects_trailing_text() {
        fails("1 2")
        fails("{} x")
        fails("[]]")
    }

    @Test fun rejects_runaway_nesting() {
        fails("[".repeat(100) + "]".repeat(100))
        // A sensible depth is fine.
        MiniJson.parse("[".repeat(10) + "]".repeat(10))
    }

    @Test fun accessors_return_null_for_the_wrong_shape() {
        assertNull(MiniJson.obj(listOf(1L)))
        assertNull(MiniJson.list("x"))
        assertTrue(MiniJson.obj(MiniJson.parse("{}")) != null)
    }
}
