package com.intercom.app.update

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** Everything that can stop an update, each with its own words in the UI. */
enum class UpdateError {
    Offline, RateLimited, NoRelease, ServerError,
    BadResponse, BadTag, NoApk, NoChecksum, BadHost, BadSize,
    /** The downloaded file's SHA-256 is not the published one. It is deleted. */
    ChecksumMismatch,
    /** More or fewer bytes than the release said. */
    SizeMismatch,
    Storage,
    /** The file is not an APK of this app. */
    WrongPackage,
    /** Signed with a different key than the installed app; Android would refuse it. */
    WrongSignature,
    /** Not a higher version code than the installed app. */
    NotNewer,
    Unreadable,
    /** A call is running; installing would end it. */
    CallActive,
    Cancelled,
    ;

    companion object {
        fun of(p: UpdateProblem): UpdateError = when (p) {
            UpdateProblem.BadResponse -> BadResponse
            UpdateProblem.BadTag -> BadTag
            UpdateProblem.NoApk -> NoApk
            UpdateProblem.NoChecksum -> NoChecksum
            UpdateProblem.BadHost -> BadHost
            UpdateProblem.BadSize -> BadSize
        }
    }
}

class UpdateException(val error: UpdateError, cause: Throwable? = null) : Exception(error.name, cause)

/** Copies a download to disk while hashing it, so the file is never read back or held in memory. */
object ApkStream {

    class Result(val sha256: String, val bytes: Long)

    /**
     * Streams [input] to [out] in 64 KB pieces. Stops with [UpdateError.Cancelled] when
     * [isCancelled] says so, [UpdateError.SizeMismatch] when the byte count is not
     * [expectedSize] (checked while reading, so an endless stream is cut off at the limit),
     * and returns the lower-case hex SHA-256 of what was written.
     */
    fun copy(
        input: InputStream,
        out: OutputStream,
        expectedSize: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (isCancelled()) throw UpdateException(UpdateError.Cancelled)
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > expectedSize) throw UpdateException(UpdateError.SizeMismatch)
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
            onProgress(total)
        }
        if (total != expectedSize) throw UpdateException(UpdateError.SizeMismatch)
        return Result(hex(digest.digest()), total)
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            append("0123456789abcdef"[b.toInt() and 0xF])
        }
    }

    /** Case-insensitive compare of two hex digests. */
    fun sameDigest(a: String, b: String): Boolean = a.equals(b, ignoreCase = true) && a.length == 64
}

/**
 * The only network code in the app. HTTPS to GitHub's own hosts, redirects followed by hand
 * so every hop is checked against [UpdateHosts], short timeouts, a size cap on text replies,
 * no cookies, no credentials.
 */
object UpdateHttp {

    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Opens [url] and returns the connection ready to read, or throws [UpdateException]. */
    fun open(url: String, userAgent: String, accept: String): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!UpdateHosts.isAllowed(current)) throw UpdateException(UpdateError.BadHost)
            val conn = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", accept)
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
            } catch (e: IOException) {
                throw UpdateException(UpdateError.Offline, e)
            }
            try {
                val code = conn.responseCode
                when {
                    code == 200 -> return conn
                    code in listOf(301, 302, 303, 307, 308) -> {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank()) throw UpdateException(UpdateError.BadResponse)
                        current = URI(current).resolve(loc).toString()
                    }
                    code == 404 -> { conn.disconnect(); throw UpdateException(UpdateError.NoRelease) }
                    code == 403 || code == 429 -> {
                        conn.disconnect()
                        throw UpdateException(UpdateError.RateLimited)
                    }
                    else -> { conn.disconnect(); throw UpdateException(UpdateError.ServerError) }
                }
            } catch (e: UpdateException) {
                throw e
            } catch (e: UnknownHostException) {
                conn.disconnect(); throw UpdateException(UpdateError.Offline, e)
            } catch (e: SocketTimeoutException) {
                conn.disconnect(); throw UpdateException(UpdateError.Offline, e)
            } catch (e: IOException) {
                conn.disconnect(); throw UpdateException(UpdateError.Offline, e)
            } catch (e: IllegalArgumentException) {
                conn.disconnect(); throw UpdateException(UpdateError.BadResponse, e)
            }
        }
        throw UpdateException(UpdateError.BadResponse)
    }

    /** A small text reply (the release document, a .sha256 file), refused if bigger than [maxBytes]. */
    fun getText(url: String, userAgent: String, accept: String, maxBytes: Int): String {
        val conn = open(url, userAgent, accept)
        try {
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw UpdateException(UpdateError.BadResponse)
                    out.write(buf, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(UpdateError.Offline, e)
        } finally {
            conn.disconnect()
        }
    }
}
