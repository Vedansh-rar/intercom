package com.intercom.app.update

/**
 * A small JSON reader for the one document the update check fetches.
 *
 * It exists so the parsing can be unit-tested on the JVM (the Android `org.json` classes are
 * stubs there) and so the app takes on no new library. Objects become [Map], arrays [List],
 * numbers [Long] or [Double]; strings, booleans and null are themselves.
 *
 * It is strict: trailing garbage, unterminated strings and absurd nesting are errors.
 */
object MiniJson {

    class ParseError(message: String) : Exception(message)

    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw ParseError("trailing text at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?>? = v as? List<Any?>

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw ParseError("$msg at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deep")
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return v
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (peek() != '"') fail("expected a key")
                val k = string()
                skipWs()
                if (peek() != ':') fail("expected ':'")
                pos++
                skipWs()
                out[k] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWs()
                out += value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else fail("unexpected end")

        private fun string(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("short \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true; pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            return (if (isDouble) text.toDoubleOrNull() else text.toLongOrNull()) ?: fail("bad number '$text'")
        }
    }
}
