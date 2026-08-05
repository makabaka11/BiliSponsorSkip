package com.retrsoft.bilisponsorskip

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal class SponsorBlockClient {
    data class Segment(
        val startMs: Int,
        val endMs: Int,
        val uuid: String,
        val category: String,
        val votes: Int = 0,
    )

    data class MutationResult(
        val successful: Boolean,
        val statusCode: Int,
        val message: String,
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

    fun submitSegment(
        bvid: String,
        cid: String,
        userId: String,
        category: String,
        startMs: Int,
        endMs: Int,
        durationMs: Int,
    ): MutationResult {
        val body = createSubmitPayload(bvid, cid, userId, category, startMs, endMs, durationMs)
        return executeMutation("$SERVER_URL/api/skipSegments", body.toString())
    }

    internal fun createSubmitPayload(
        bvid: String,
        cid: String,
        userId: String,
        category: String,
        startMs: Int,
        endMs: Int,
        durationMs: Int,
    ): JSONObject {
        val localUuid = Identity.generate()
        val segment = JSONObject().apply {
            put("cid", cid)
            put("segment", JSONArray().put(millisecondsToSeconds(startMs)).put(millisecondsToSeconds(endMs)))
            put("UUID", localUuid)
            put("category", category)
            put("actionType", "skip")
            put("source", 1)
        }
        return JSONObject().apply {
            put("videoID", bvid)
            put("cid", cid)
            put("userID", userId)
            put("segments", JSONArray().put(segment))
            put("videoDuration", millisecondsToSeconds(durationMs))
            put("userAgent", "BiliSponsorSkip/$CLIENT_VERSION")
        }
    }

    fun vote(uuid: String, userId: String, type: Int): MutationResult {
        val url = "$SERVER_URL/api/voteOnSponsorTime" +
            "?UUID=${urlEncode(uuid)}&userID=${urlEncode(userId)}&type=$type"
        return executeMutation(url, body = null)
    }

    fun setUsername(userId: String, username: String): MutationResult {
        val url = "$SERVER_URL/api/setUsername" +
            "?userID=${urlEncode(userId)}&username=${urlEncode(username)}"
        return executeMutation(url, body = null)
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

                    add(Segment(startMs, endMs, item.optString("UUID"), category, item.optInt("votes", 0)))
                }
            }.sortedBy(Segment::startMs)
        }
        return emptyList()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun executeMutation(url: String, body: String?): MutationResult {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", "BiliSponsorSkip")
            connection.setRequestProperty("X-Ext-Version", CLIENT_VERSION)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            MutationResult(
                successful = status in 200..299,
                statusCode = status,
                message = response.ifBlank { "HTTP $status" },
            )
        } catch (error: Throwable) {
            MutationResult(false, -1, error.message ?: error.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun millisecondsToSeconds(value: Int): Double =
        kotlin.math.round(value.coerceAtLeast(0) / 1000.0 * 1000.0) / 1000.0

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val SERVER_URL = "https://www.bsbsb.top"
        const val BASE_URL = "$SERVER_URL/api/skipSegments/"
        const val CLIENT_VERSION = "0.2.4"
    }
}
