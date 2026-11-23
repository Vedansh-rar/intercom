package com.intercom.app.update

import java.net.URI

/** One file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** What GitHub's "latest release" document says, reduced to what the update check uses. */
data class Release(
    val tag: String,
    val version: SemVer,
    val notes: String,
    val assets: List<ReleaseAsset>,
)

/** An update the rider can be offered: a newer version, its APK, and a checksum for it. */
data class UpdateOffer(
    val version: SemVer,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 the download must match. */
    val sha256: String,
    val notes: String,
)

/** Why a release cannot be offered. Each one is shown to the rider in words. */
enum class UpdateProblem {
    /** The reply was not a release document. */
    BadResponse,
    /** The tag is not a version, so "newer" cannot be decided. */
    BadTag,
    /** No APK attached, or several and no way to tell which. */
    NoApk,
    /** No checksum published for the APK (or two different ones). */
    NoChecksum,
    /** A download address is not an https GitHub address. */
    BadHost,
    /** The APK is empty or larger than any real build. */
    BadSize,
}

sealed interface ReleaseCheck {
    data class Offer(val offer: UpdateOffer) : ReleaseCheck
    data class UpToDate(val latest: SemVer) : ReleaseCheck
    data class Problem(val problem: UpdateProblem) : ReleaseCheck
}

/** Turns GitHub's JSON into a [Release], and a [Release] plus the running version into a decision. */
object ReleaseParser {

    /** No Rider Intercom build is anywhere near this; a bigger "APK" is not ours. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    fun parse(json: String): Release? {
        val parsed = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.ParseError) {
            return null
        }
        val root = MiniJson.obj(parsed) ?: return null
        // The "latest" endpoint never returns these, but a mirrored or hand-edited document might.
        if (root["draft"] == true) return null
        val tag = root["tag_name"] as? String ?: return null
        val version = SemVer.parse(tag) ?: return null
        val assets = MiniJson.list(root["assets"]).orEmpty().mapNotNull { a ->
            val o = MiniJson.obj(a) ?: return@mapNotNull null
            val name = o["name"] as? String ?: return@mapNotNull null
            val url = o["browser_download_url"] as? String ?: return@mapNotNull null
            ReleaseAsset(name, url, (o["size"] as? Long) ?: -1L)
        }
        return Release(tag, version, root["body"] as? String ?: "", assets)
    }

    /**
     * Picks the APK: ignores unsigned and debug builds, prefers a name containing "release",
     * and gives up (null) rather than guess between several equally good candidates.
     */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter {
            val n = it.name.lowercase()
            n.endsWith(".apk") && "unsigned" !in n && "debug" !in n
        }
        val release = apks.filter { "release" in it.name.lowercase() }
        return when {
            release.size == 1 -> release[0]
            release.isEmpty() && apks.size == 1 -> apks[0]
            else -> null
        }
    }

    /** The checksum asset for [apk]: `<apk>.sha256` or `<apk>.sha256sum`, exact name only. */
    fun checksumAsset(assets: List<ReleaseAsset>, apk: ReleaseAsset): ReleaseAsset? {
        val wanted = setOf("${apk.name}.sha256", "${apk.name}.sha256sum").map { it.lowercase() }
        return assets.firstOrNull { it.name.lowercase() in wanted }
    }

    /**
     * Decides what to do with [release] when the app is [current]. The checksum text of the
     * `.sha256` asset, if there is one, is passed in by the caller (it needs a download).
     */
    fun decide(release: Release, current: SemVer, checksumText: String?): ReleaseCheck {
        if (!release.version.isNewerThan(current)) return ReleaseCheck.UpToDate(release.version)

        val apk = pickApk(release.assets) ?: return ReleaseCheck.Problem(UpdateProblem.NoApk)
        if (apk.size <= 0 || apk.size > MAX_APK_BYTES) return ReleaseCheck.Problem(UpdateProblem.BadSize)
        if (!UpdateHosts.isAllowed(apk.url)) return ReleaseCheck.Problem(UpdateProblem.BadHost)

        val fromAsset = checksumText?.let { Checksum.fromFile(it, apk.name) }
        val sha = fromAsset ?: Checksum.fromNotes(release.notes, apk.name)
            ?: return ReleaseCheck.Problem(UpdateProblem.NoChecksum)

        return ReleaseCheck.Offer(
            UpdateOffer(release.version, release.tag, apk.name, apk.url, apk.size, sha, release.notes.take(2000)),
        )
    }
}

/** Where downloads may come from: https, and only GitHub's own hosts. */
object UpdateHosts {
    private val EXACT = setOf("github.com", "api.github.com")
    private const val ASSET_DOMAIN = ".githubusercontent.com"

    fun isAllowed(url: String): Boolean {
        val u = try { URI(url) } catch (e: Exception) { return false }
        if (!"https".equals(u.scheme, ignoreCase = true)) return false
        if (u.userInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        val host = u.host?.lowercase() ?: return false
        return host in EXACT || host.endsWith(ASSET_DOMAIN)
    }
}

/** Finding the SHA-256 in a `.sha256` file or in the release notes. */
object Checksum {
    private val HEX64 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
    private val GNU_LINE = Regex("^\\s*([0-9a-fA-F]{64})\\s+\\*?(.+?)\\s*$")
    private val BSD_LINE = Regex("^\\s*SHA-?256\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$", RegexOption.IGNORE_CASE)

    /**
     * `sha256sum` output ("<hash>  <name>"), the BSD form, or a bare hash. A line that names
     * a different file is not used; a bare hash is accepted only when it is the only content.
     */
    fun fromFile(text: String, apkName: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (l in lines) {
            GNU_LINE.matchEntire(l)?.let { m ->
                if (baseName(m.groupValues[2]) == apkName) return m.groupValues[1].lowercase()
            }
            BSD_LINE.matchEntire(l)?.let { m ->
                if (baseName(m.groupValues[1]) == apkName) return m.groupValues[2].lowercase()
            }
        }
        if (lines.size == 1 && HEX64.matchEntire(lines[0]) != null) return lines[0].lowercase()
        return null
    }

    /**
     * Release notes: a line that has a 64-hex hash and either the APK's name or the word
     * sha256. Two different hashes found means the notes are ambiguous, so none is used.
     */
    fun fromNotes(notes: String, apkName: String): String? {
        val found = notes.lines().flatMap { line ->
            val hashes = HEX64.findAll(line).map { it.value.lowercase() }.toList()
            if (hashes.isEmpty()) emptyList()
            else if (apkName in line || Regex("sha-?256", RegexOption.IGNORE_CASE).containsMatchIn(line)) hashes
            else emptyList()
        }.toSet()
        return found.singleOrNull()
    }

    private fun baseName(path: String) = path.trim().trim('"').substringAfterLast('/').substringAfterLast('\\')
}

/** When the opt-in daily check should run. */
object UpdatePolicy {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Never during a call, and never unless the rider switched it on. A clock set back counts as due. */
    fun shouldAutoCheck(enabled: Boolean, lastCheckMs: Long, nowMs: Long, callActive: Boolean): Boolean {
        if (!enabled || callActive) return false
        if (lastCheckMs <= 0 || lastCheckMs > nowMs) return true
        return nowMs - lastCheckMs >= INTERVAL_MS
    }
}
