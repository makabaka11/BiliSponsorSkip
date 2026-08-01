package com.retrsoft.bilisponsorskip

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal class SponsorBlockClient {
    data class Segment(
        val startMs: Int,
        val endMs: Int,
        val uuid: String,
        val category: String,
    )

    sealed interface Result {
        data class Success(val segments: List<Segment>) : Result
        data class Failure(val retryable: Boolean, val message: String) : Result
    }

    fun getSponsorSegments(bvid: String, cid: String): Result {
        val prefix = sha256(bvid.trim()).take(4)
        val connection = URI.create("$BASE_URL$prefix").toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", "BiliSponsorSkip")
            connection.setRequestProperty("X-Ext-Version", "0.13.0")

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Result.Success(parseSegments(json, bvid, cid))
                }

                HttpURLConnection.HTTP_NOT_FOUND -> Result.Success(emptyList())
                in 500..599 -> Result.Failure(true, "server returned HTTP $code")
                else -> Result.Failure(false, "server returned HTTP $code")
            }
        } catch (error: Throwable) {
            Result.Failure(true, error.message ?: error.javaClass.name)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseSegments(json: String, bvid: String, cid: String): List<Segment> {
        val response = JSONArray(json)
        for (index in 0 until response.length()) {
            val video = response.optJSONObject(index) ?: continue
            if (video.optString("videoID") != bvid) continue

            val items = video.optJSONArray("segments") ?: return emptyList()
            return buildList {
                for (itemIndex in 0 until items.length()) {
                    val item = items.optJSONObject(itemIndex) ?: continue
                    if (item.optString("cid") != cid) continue
                    val category = item.optString("category")
                    if (category.isBlank()) continue

                    val actionType = item.optString("actionType", "skip")
                    if (actionType != "skip") continue

                    val range = item.optJSONArray("segment") ?: continue
                    if (range.length() < 2) continue
                    val startMs = (range.optDouble(0, -1.0) * 1000).toInt()
                    val endMs = (range.optDouble(1, -1.0) * 1000).toInt()
                    if (startMs < 0 || endMs <= startMs) continue

                    add(Segment(startMs, endMs, item.optString("UUID"), category))
                }
            }.sortedBy(Segment::startMs)
        }
        return emptyList()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BASE_URL = "https://www.bsbsb.top/api/skipSegments/"
    }
}
