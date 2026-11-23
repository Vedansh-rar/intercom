package com.intercom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumTest {

    private val a = "0123456789abcdef".repeat(4)
    private val b = "fedcba9876543210".repeat(4)
    private val apk = "app-release.apk"

    @Test fun reads_sha256sum_output() {
        assertEquals(a, Checksum.fromFile("$a  $apk\n", apk))
        assertEquals(a, Checksum.fromFile("$a *$apk", apk))
        assertEquals(a, Checksum.fromFile("$a  ./build/outputs/$apk", apk))
        assertEquals(a, Checksum.fromFile("$a  C:\\out\\$apk", apk))
    }

    @Test fun reads_bsd_form() {
        assertEquals(a, Checksum.fromFile("SHA256 ($apk) = $a", apk))
        assertEquals(a, Checksum.fromFile("sha-256 ($apk)=$a", apk))
    }

    @Test fun reads_a_bare_hash_only_when_it_is_the_whole_file() {
        assertEquals(a, Checksum.fromFile("$a\n", apk))
        assertEquals(a, Checksum.fromFile("  ${a.uppercase()}  ", apk))
        assertNull(Checksum.fromFile("$a\n$b\n", apk))
    }

    @Test fun picks_the_line_for_this_apk_among_several() {
        assertEquals(b, Checksum.fromFile("$a  other.apk\n$b  $apk\n", apk))
    }

    @Test fun refuses_a_hash_for_another_file() {
        assertNull(Checksum.fromFile("$a  other.apk", apk))
        assertNull(Checksum.fromFile("$a  $apk.bak", apk))
    }

    @Test fun refuses_garbage_and_wrong_lengths() {
        assertNull(Checksum.fromFile("", apk))
        assertNull(Checksum.fromFile("hello", apk))
        assertNull(Checksum.fromFile("${a.dropLast(1)}  $apk", apk))
        assertNull(Checksum.fromFile("${a}0  $apk", apk))
        assertNull(Checksum.fromFile("${"z".repeat(64)}  $apk", apk))
    }

    @Test fun notes_with_a_labelled_hash() {
        assertEquals(a, Checksum.fromNotes("Changes\nSHA-256: $a\n", apk))
        assertEquals(a, Checksum.fromNotes("$apk  $a", apk))
        assertEquals(a, Checksum.fromNotes("sha256 = ${a.uppercase()}", apk))
    }

    @Test fun notes_ignore_unlabelled_hashes() {
        assertNull(Checksum.fromNotes("commit $a", apk))
    }

    @Test fun notes_with_two_different_hashes_are_ambiguous() {
        assertNull(Checksum.fromNotes("sha256: $a\nsha256: $b", apk))
    }

    @Test fun notes_repeating_the_same_hash_are_fine() {
        assertEquals(a, Checksum.fromNotes("sha256: $a\n$apk $a", apk))
    }

    @Test fun a_longer_hex_run_is_not_a_hash() {
        assertNull(Checksum.fromNotes("sha256: ${a}00", apk))
    }
}
