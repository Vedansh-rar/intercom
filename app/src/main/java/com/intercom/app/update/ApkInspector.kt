package com.intercom.app.update

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * A last look at the downloaded file before the system installer sees it: is it this app,
 * is it newer, and is it signed with the same key as the installed copy? Android enforces
 * the last one anyway; checking first turns a cryptic "App not installed" into a sentence.
 */
object ApkInspector {

    /** What the three checks need, so the decision can be unit-tested without a phone. */
    data class Facts(
        val ourPackage: String,
        val theirPackage: String?,
        val ourVersionCode: Long,
        val theirVersionCode: Long,
        val ourSigners: List<ByteArray>?,
        val theirSigners: List<ByteArray>?,
    )

    /** Null means all three checks pass. */
    fun problem(f: Facts): UpdateError? = when {
        f.theirPackage == null -> UpdateError.Unreadable
        f.theirPackage != f.ourPackage -> UpdateError.WrongPackage
        f.theirVersionCode <= f.ourVersionCode -> UpdateError.NotNewer
        f.ourSigners == null || f.theirSigners == null -> UpdateError.WrongSignature
        !sameSigners(f.ourSigners, f.theirSigners) -> UpdateError.WrongSignature
        else -> null
    }

    fun sameSigners(a: List<ByteArray>, b: List<ByteArray>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val sa = a.map { it.toList() }.toSet()
        val sb = b.map { it.toList() }.toSet()
        return sa == sb
    }

    fun inspect(context: Context, file: File): UpdateError? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val ours = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            return UpdateError.Unreadable
        }
        val theirs = try {
            pm.getPackageArchiveInfo(file.path, flags)
        } catch (e: Exception) {
            null
        }
        return problem(
            Facts(
                ourPackage = context.packageName,
                theirPackage = theirs?.packageName,
                ourVersionCode = ours.longVersionCode,
                theirVersionCode = theirs?.longVersionCode ?: 0L,
                ourSigners = ours.signingInfo?.apkContentsSigners?.map { it.toByteArray() },
                theirSigners = theirs?.signingInfo?.apkContentsSigners?.map { it.toByteArray() },
            ),
        )
    }
}
