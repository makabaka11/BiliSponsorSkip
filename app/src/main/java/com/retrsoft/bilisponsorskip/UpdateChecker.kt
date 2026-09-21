package com.retrsoft.bilisponsorskip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal class UpdateChecker {
    data class LatestRelease(
        val tagName: String,
        val updateAvailable: Boolean,
    )

    fun check(currentVersion: String): LatestRelease? {
        val connection = URI.create(LATEST_RELEASE_API_URL).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "BiliSponsorSkip/$currentVersion")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val tagName = JSONObject(json).optString("tag_name").trim()
            if (tagName.isBlank()) null else LatestRelease(
                tagName = tagName,
                updateAvailable = isVersionNewer(tagName, currentVersion),
            )
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val LATEST_RELEASE_PAGE_URL =
            "https://github.com/makabaka11/BiliSponsorSkip/releases/latest"
        private const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/makabaka11/BiliSponsorSkip/releases/latest"
    }
}

internal fun isVersionNewer(latestTag: String, currentVersion: String): Boolean {
    val latest = parseVersion(latestTag) ?: return false
    val current = parseVersion(currentVersion) ?: return false
    val count = maxOf(latest.size, current.size)
    for (index in 0 until count) {
        val latestPart = latest.getOrElse(index) { 0L }
        val currentPart = current.getOrElse(index) { 0L }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

private fun parseVersion(value: String): List<Long>? {
    val match = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[-+].*)?$""").matchEntire(value.trim()) ?: return null
    return match.groupValues[1].split('.').map { part -> part.toLongOrNull() ?: return null }
}
