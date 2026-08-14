package com.trustedconfigurator.browser.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A release newer than the one running. */
data class AvailableUpdate(
    val versionName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
)

/**
 * Checks GitHub releases for a newer build.
 *
 * The pages this browser loads are always current because they are fetched live;
 * the app around them is not, so it has to say when it has fallen behind. It
 * only ever *tells* you — downloading and installing is the system's job, via
 * the release page in a real browser.
 */
object UpdateChecker {

    const val RELEASES_API = "https://api.github.com/repos/SchoepsLabs/webserial-android/releases/latest"
    const val RELEASES_PAGE = "https://github.com/SchoepsLabs/webserial-android/releases/latest"

    /**
     * Compares dotted numeric versions, ignoring a leading "v".
     *
     * @return positive when [candidate] is newer than [current], 0 when equal,
     * negative when older. Non-numeric junk sorts as 0 so a malformed tag can
     * never masquerade as an upgrade.
     */
    fun compareVersions(candidate: String?, current: String?): Int {
        val left = parse(candidate)
        val right = parse(current)
        val length = maxOf(left.size, right.size)
        for (i in 0 until length) {
            val a = left.getOrElse(i) { 0 }
            val b = right.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun parse(version: String?): List<Int> {
        if (version.isNullOrBlank()) return emptyList()
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            // Drop any pre-release or build suffix: 1.2.3-rc1 compares as 1.2.3.
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }

    /**
     * @param json the GitHub "latest release" payload.
     * @return the update, or null when the payload is unusable or not newer.
     */
    fun parseRelease(json: String?, currentVersion: String): AvailableUpdate? {
        if (json.isNullOrBlank()) return null
        return try {
            val release = JSONObject(json)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                return null
            }
            val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            if (compareVersions(tag, currentVersion) <= 0) return null

            AvailableUpdate(
                versionName = tag.removePrefix("v"),
                title = release.optString("name").takeIf { it.isNotBlank() } ?: tag,
                notes = release.optString("body", ""),
                pageUrl = release.optString("html_url").takeIf { it.isNotBlank() } ?: RELEASES_PAGE,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Blocking network call; run it off the main thread. @return null on any failure. */
    fun fetchLatest(currentVersion: String): AvailableUpdate? = try {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "webserial-android")
        }
        try {
            if (connection.responseCode == 200) {
                parseRelease(connection.inputStream.bufferedReader().use { it.readText() }, currentVersion)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        // An update check is never worth surfacing an error for.
        null
    }
}
