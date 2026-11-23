package com.intercom.app.update

/**
 * Semantic versions as the update check needs them: parse a release tag, compare two
 * versions the way semver.org says, and never call an older or equal version "newer".
 *
 * Tags such as "v1.2", "1.0-phase1" and "2.0.0-rc.1+build5" are accepted; a missing minor
 * or patch counts as 0. Anything else (a tag like "nightly") does not parse, and the
 * caller must treat that release as not offerable rather than guess.
 */
data class SemVer(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val pre: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A release is newer than any of its own pre-releases.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val c = compareIdentifier(pre[i], other.pre[i])
            if (c != 0) return c
        }
        return compareValues(pre.size, other.pre.size)
    }

    fun isNewerThan(other: SemVer): Boolean = compareTo(other) > 0

    override fun toString(): String =
        "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val NUMERIC = Regex("[0-9]+")
        private val IDENT = Regex("[0-9A-Za-z-]+")

        /** Null when [raw] is not a version. */
        fun parse(raw: String?): SemVer? {
            var s = raw?.trim() ?: return null
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            val plus = s.indexOf('+')
            if (plus >= 0) {
                if (!s.substring(plus + 1).split('.').all { it.isNotEmpty() && IDENT.matches(it) }) return null
                s = s.substring(0, plus)
            }
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1).split('.') else emptyList()
            if (dash >= 0 && pre.any { it.isEmpty() || !IDENT.matches(it) }) return null
            // A numeric identifier must not have leading zeros (semver rule 9).
            if (pre.any { NUMERIC.matches(it) && it.length > 1 && it.startsWith("0") }) return null

            val parts = core.split('.')
            if (parts.size !in 1..3) return null
            val nums = parts.map { p ->
                if (!NUMERIC.matches(p) || p.length > 9) return null
                p.toLong()
            }
            return SemVer(nums[0], nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 }, pre)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = NUMERIC.matches(a)
            val bn = NUMERIC.matches(b)
            return when {
                an && bn -> a.toLong().compareTo(b.toLong())
                an -> -1          // numeric identifiers sort below alphanumeric ones
                bn -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
