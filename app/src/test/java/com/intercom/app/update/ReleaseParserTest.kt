package com.intercom.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {

    private val sha = "a".repeat(64)
    private val sha2 = "b".repeat(64)
    private val base = "https://github.com/Vedansh-rar/intercom/releases/download/v2.0.0"

    private fun asset(name: String, size: Long = 5_000_000, url: String = "$base/$name") = ReleaseAsset(name, url, size)

    private fun release(
        tag: String = "v2.0.0",
        notes: String = "",
        assets: List<ReleaseAsset> = listOf(asset("rider-intercom-release.apk")),
    ) = Release(tag, SemVer.parse(tag)!!, notes, assets)

    private val current = SemVer.parse("1.0-phase1")!!

    // ---- parse

    @Test fun parses_a_github_release_document() {
        val json = """
            {"tag_name":"v2.0.0","draft":false,"prerelease":false,"body":"Notes\nsha256: $sha",
             "assets":[{"name":"app-release.apk","size":123456,"browser_download_url":"$base/app-release.apk","extra":1},
                       {"name":"app-release.apk.sha256","size":90,"browser_download_url":"$base/app-release.apk.sha256"}]}
        """.trimIndent()
        val r = ReleaseParser.parse(json)!!
        assertEquals("v2.0.0", r.tag)
        assertEquals(SemVer(2, 0, 0), r.version)
        assertEquals(2, r.assets.size)
        assertEquals(123456L, r.assets[0].size)
        assertTrue(r.notes.contains(sha))
    }

    @Test fun parse_rejects_things_that_are_not_usable_releases() {
        assertNull(ReleaseParser.parse(""))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("[]"))
        assertNull(ReleaseParser.parse("""{"message":"Not Found"}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":"nightly","assets":[]}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":5,"assets":[]}"""))
        assertNull(ReleaseParser.parse("""{"tag_name":"v2.0.0","draft":true,"assets":[]}"""))
    }

    @Test fun parse_skips_malformed_assets_but_keeps_the_rest() {
        val json = """{"tag_name":"v2.0.0","assets":[1,{"name":"x"},{"name":"a.apk","browser_download_url":"$base/a.apk"}]}"""
        val r = ReleaseParser.parse(json)!!
        assertEquals(listOf("a.apk"), r.assets.map { it.name })
        assertEquals(-1L, r.assets[0].size)
    }

    // ---- pickApk

    @Test fun picks_the_only_apk() {
        assertEquals("only.apk", ReleaseParser.pickApk(listOf(asset("only.apk"), asset("notes.txt")))?.name)
    }

    @Test fun ignores_debug_and_unsigned_builds() {
        val picked = ReleaseParser.pickApk(listOf(asset("app-debug.apk"), asset("app-release-unsigned.apk"), asset("app-release.apk")))
        assertEquals("app-release.apk", picked?.name)
        assertNull(ReleaseParser.pickApk(listOf(asset("app-debug.apk"), asset("app-release-unsigned.apk"))))
    }

    @Test fun prefers_the_release_named_apk_among_several() {
        val picked = ReleaseParser.pickApk(listOf(asset("other.apk"), asset("rider-release.apk")))
        assertEquals("rider-release.apk", picked?.name)
    }

    @Test fun refuses_to_guess_when_ambiguous() {
        assertNull(ReleaseParser.pickApk(listOf(asset("a.apk"), asset("b.apk"))))
        assertNull(ReleaseParser.pickApk(listOf(asset("a-release.apk"), asset("b-release.apk"))))
        assertNull(ReleaseParser.pickApk(emptyList()))
    }

    @Test fun apk_matching_ignores_case_of_extension() {
        assertNotNull(ReleaseParser.pickApk(listOf(asset("Rider-Release.APK"))))
    }

    // ---- checksumAsset

    @Test fun finds_the_checksum_asset_by_exact_name() {
        val apk = asset("app-release.apk")
        val s = asset("app-release.apk.sha256")
        assertEquals(s, ReleaseParser.checksumAsset(listOf(apk, s), apk))
        val s2 = asset("app-release.apk.sha256sum")
        assertEquals(s2, ReleaseParser.checksumAsset(listOf(apk, s2), apk))
    }

    @Test fun does_not_accept_a_checksum_for_a_different_file() {
        val apk = asset("app-release.apk")
        assertNull(ReleaseParser.checksumAsset(listOf(apk, asset("other.apk.sha256"), asset("app-release.sha256")), apk))
    }

    // ---- decide

    @Test fun offers_a_newer_release_with_a_checksum_file() {
        val d = ReleaseParser.decide(release(), current, "$sha  rider-intercom-release.apk\n")
        val offer = (d as ReleaseCheck.Offer).offer
        assertEquals(sha, offer.sha256)
        assertEquals(SemVer(2, 0, 0), offer.version)
        assertEquals("rider-intercom-release.apk", offer.apkName)
        assertEquals(5_000_000L, offer.apkSize)
    }

    @Test fun falls_back_to_the_release_notes_for_the_checksum() {
        val d = ReleaseParser.decide(release(notes = "SHA-256: ${sha.uppercase()}"), current, null)
        assertEquals(sha, (d as ReleaseCheck.Offer).offer.sha256)
    }

    @Test fun no_checksum_anywhere_means_no_offer() {
        val d = ReleaseParser.decide(release(notes = "just notes"), current, null)
        assertEquals(ReleaseCheck.Problem(UpdateProblem.NoChecksum), d)
    }

    @Test fun a_checksum_file_for_another_apk_is_not_used() {
        val d = ReleaseParser.decide(release(), current, "$sha  something-else.apk")
        assertEquals(ReleaseCheck.Problem(UpdateProblem.NoChecksum), d)
    }

    @Test fun same_or_older_version_is_up_to_date_and_never_offered() {
        assertTrue(ReleaseParser.decide(release("v1.0-phase1"), current, "$sha  rider-intercom-release.apk") is ReleaseCheck.UpToDate)
        assertTrue(ReleaseParser.decide(release("v0.9.0"), current, "$sha  rider-intercom-release.apk") is ReleaseCheck.UpToDate)
        assertTrue(ReleaseParser.decide(release("v1.0.0-rc.1"), SemVer.parse("1.0.0")!!, null) is ReleaseCheck.UpToDate)
    }

    @Test fun up_to_date_does_not_need_an_apk_or_checksum() {
        val d = ReleaseParser.decide(release("v1.0-phase1", assets = emptyList()), current, null)
        assertTrue(d is ReleaseCheck.UpToDate)
    }

    @Test fun missing_apk_is_reported() {
        val d = ReleaseParser.decide(release(assets = emptyList()), current, "$sha  x.apk")
        assertEquals(ReleaseCheck.Problem(UpdateProblem.NoApk), d)
    }

    @Test fun bad_sizes_are_refused() {
        for (size in listOf(0L, -1L, ReleaseParser.MAX_APK_BYTES + 1)) {
            val d = ReleaseParser.decide(release(assets = listOf(asset("a-release.apk", size))), current, "$sha  a-release.apk")
            assertEquals("size $size", ReleaseCheck.Problem(UpdateProblem.BadSize), d)
        }
        val ok = ReleaseParser.decide(release(assets = listOf(asset("a-release.apk", ReleaseParser.MAX_APK_BYTES))), current, "$sha  a-release.apk")
        assertTrue(ok is ReleaseCheck.Offer)
    }

    @Test fun apk_on_a_foreign_host_is_refused() {
        val evil = asset("a-release.apk", url = "https://evil.example.com/a-release.apk")
        val d = ReleaseParser.decide(release(assets = listOf(evil)), current, "$sha  a-release.apk")
        assertEquals(ReleaseCheck.Problem(UpdateProblem.BadHost), d)
        val http = asset("a-release.apk", url = "http://github.com/a-release.apk")
        assertEquals(ReleaseCheck.Problem(UpdateProblem.BadHost), ReleaseParser.decide(release(assets = listOf(http)), current, "$sha  a-release.apk"))
    }

    @Test fun long_notes_are_truncated_for_display() {
        val d = ReleaseParser.decide(release(notes = "x".repeat(5000) + " sha256 $sha"), current, "$sha  rider-intercom-release.apk")
        assertTrue((d as ReleaseCheck.Offer).offer.notes.length <= 2000)
    }

    @Test fun error_mapping_covers_every_problem() {
        for (p in UpdateProblem.values()) assertNotNull(UpdateError.of(p))
        assertEquals(UpdateError.NoChecksum, UpdateError.of(UpdateProblem.NoChecksum))
        assertEquals(UpdateError.BadHost, UpdateError.of(UpdateProblem.BadHost))
    }
}
