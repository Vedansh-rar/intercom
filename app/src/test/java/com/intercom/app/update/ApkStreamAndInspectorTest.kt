package com.intercom.app.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkStreamAndInspectorTest {

    private fun sha(b: ByteArray) = ApkStream.hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun data(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun copy(
        input: InputStream,
        expected: Long,
        out: ByteArrayOutputStream = ByteArrayOutputStream(),
        cancelled: () -> Boolean = { false },
        progress: (Long) -> Unit = {},
    ) = ApkStream.copy(input, out, expected, cancelled, progress)

    private fun errorOf(block: () -> Unit): UpdateError? = try {
        block(); null
    } catch (e: UpdateException) {
        e.error
    }

    // ---- ApkStream

    @Test fun streams_and_hashes_exactly_what_it_writes() {
        val d = data(300_000)
        val out = ByteArrayOutputStream()
        val r = copy(ByteArrayInputStream(d), d.size.toLong(), out)
        assertArrayEquals(d, out.toByteArray())
        assertEquals(sha(d), r.sha256)
        assertEquals(d.size.toLong(), r.bytes)
    }

    @Test fun hex_is_lower_case_and_zero_padded() {
        assertEquals("000fff10", ApkStream.hex(byteArrayOf(0, 0x0f, 0xff.toByte(), 0x10)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(ByteArray(0)))
    }

    @Test fun reports_progress_up_to_the_total() {
        val d = data(200_000)
        var last = 0L
        var calls = 0
        copy(ByteArrayInputStream(d), d.size.toLong()) { last = it; calls++ }
        assertEquals(d.size.toLong(), last)
        assertTrue(calls >= 2)
    }

    @Test fun a_longer_stream_is_cut_off_at_the_limit() {
        val d = data(10_000)
        val out = ByteArrayOutputStream()
        assertEquals(UpdateError.SizeMismatch, errorOf { copy(ByteArrayInputStream(d), 5_000, out) })
        assertTrue("never writes past the expected size", out.size() <= 5_000 + 64 * 1024 && out.size() < d.size + 1)
    }

    @Test fun an_endless_stream_does_not_run_forever() {
        val endless = object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(1, off, off + len); return len }
        }
        assertEquals(UpdateError.SizeMismatch, errorOf { copy(endless, 1_000_000) })
    }

    @Test fun a_short_stream_is_a_size_mismatch() {
        assertEquals(UpdateError.SizeMismatch, errorOf { copy(ByteArrayInputStream(data(100)), 200) })
        assertEquals(UpdateError.SizeMismatch, errorOf { copy(ByteArrayInputStream(ByteArray(0)), 1) })
    }

    @Test fun cancel_stops_before_reading_more() {
        var reads = 0
        val src = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return minOf(len, 1000) }
        }
        var cancel = false
        val err = errorOf {
            copy(src, 1_000_000, cancelled = { cancel }, progress = { if (it >= 3000) cancel = true })
        }
        assertEquals(UpdateError.Cancelled, err)
        assertTrue(reads in 3..5)
    }

    @Test fun cancel_before_the_first_read() {
        assertEquals(UpdateError.Cancelled, errorOf { copy(ByteArrayInputStream(data(10)), 10, cancelled = { true }) })
    }

    @Test fun a_flipped_byte_changes_the_digest() {
        val d = data(50_000)
        val bad = d.copyOf().also { it[25_000] = (it[25_000] + 1).toByte() }
        val good = copy(ByteArrayInputStream(d), d.size.toLong())
        val tampered = copy(ByteArrayInputStream(bad), bad.size.toLong())
        assertFalse(ApkStream.sameDigest(good.sha256, tampered.sha256))
        assertTrue(ApkStream.sameDigest(good.sha256, sha(d)))
    }

    @Test fun digest_compare_is_case_insensitive_and_length_checked() {
        val h = sha(data(10))
        assertTrue(ApkStream.sameDigest(h, h.uppercase()))
        assertFalse(ApkStream.sameDigest("", ""))
        assertFalse(ApkStream.sameDigest(h.dropLast(1), h.dropLast(1)))
        assertFalse(ApkStream.sameDigest(h, "0".repeat(64)))
    }

    // ---- ApkInspector

    private val keyA = listOf(byteArrayOf(1, 2, 3))
    private val keyB = listOf(byteArrayOf(9, 9, 9))

    private fun facts(
        pkg: String? = "com.intercom.app",
        ourCode: Long = 1,
        theirCode: Long = 2,
        ours: List<ByteArray>? = keyA,
        theirs: List<ByteArray>? = keyA,
    ) = ApkInspector.Facts("com.intercom.app", pkg, ourCode, theirCode, ours, theirs)

    @Test fun a_newer_build_with_the_same_key_passes() {
        assertNull(ApkInspector.problem(facts()))
        assertNull(ApkInspector.problem(facts(theirs = listOf(byteArrayOf(1, 2, 3)))))
    }

    @Test fun unreadable_file_is_reported() {
        assertEquals(UpdateError.Unreadable, ApkInspector.problem(facts(pkg = null)))
    }

    @Test fun another_package_is_refused() {
        assertEquals(UpdateError.WrongPackage, ApkInspector.problem(facts(pkg = "com.evil.app")))
    }

    @Test fun same_or_lower_version_code_is_refused() {
        assertEquals(UpdateError.NotNewer, ApkInspector.problem(facts(theirCode = 1)))
        assertEquals(UpdateError.NotNewer, ApkInspector.problem(facts(ourCode = 5, theirCode = 3)))
    }

    @Test fun a_different_signing_key_is_refused() {
        assertEquals(UpdateError.WrongSignature, ApkInspector.problem(facts(theirs = keyB)))
    }

    @Test fun missing_or_empty_signers_are_refused() {
        assertEquals(UpdateError.WrongSignature, ApkInspector.problem(facts(theirs = null)))
        assertEquals(UpdateError.WrongSignature, ApkInspector.problem(facts(ours = null)))
        assertEquals(UpdateError.WrongSignature, ApkInspector.problem(facts(theirs = emptyList())))
    }

    @Test fun signers_must_match_as_a_set() {
        assertTrue(ApkInspector.sameSigners(keyA + keyB, keyB + keyA))
        assertFalse(ApkInspector.sameSigners(keyA, keyA + keyB))
        assertFalse(ApkInspector.sameSigners(emptyList(), emptyList()))
    }

    @Test fun every_error_maps_from_a_problem_or_stands_alone() {
        // Guards the exhaustive when() in UpdateError.of and the UI message table.
        assertEquals(UpdateProblem.values().size, UpdateProblem.values().map { UpdateError.of(it) }.toSet().size)
    }
}
