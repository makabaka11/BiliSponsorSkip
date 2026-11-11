package com.retrsoft.bilisponsorskip

import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object ServerConnection {
    const val DEFAULT_URL = "https://www.bsbsb.top"
    val presets = linkedMapOf(
        "主站" to DEFAULT_URL,
        "官方别名" to "https://bsbsb.cc",
        "官方镜像" to "http://103.236.70.57:9876",
    )

    fun normalize(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme?.lowercase(Locale.ROOT) in listOf("http", "https"))
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        uri.toASCIIString().trimEnd('/')
    }.getOrNull()

    data class ProbeResult(val available: Boolean, val elapsedMs: Long, val message: String)

    // A read-only API request, measured through the entire response body, never ICMP ping.
    fun probe(value: String): ProbeResult {
        val url = normalize(value) ?: return ProbeResult(false, 0, "地址格式无效")
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return try {
            connection = URI("$url/api/skipSegments/0000").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Origin", "BiliSponsorSkip")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 262_144) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(elapsed() < 10_000) { "请求超时" }
                }
                output.toByteArray()
            } ?: ByteArray(0)
            require(bytes.size <= 262_144) { "响应过大，无法确认 API" }
            val body = bytes.toString(Charsets.UTF_8).trim()
            val valid = when (code) {
                200 -> runCatching { JSONArray(body) }.isSuccess
                404 -> body.isEmpty() || body == "Not Found" || body == "[]"
                else -> false
            }
            ProbeResult(valid, elapsed(), if (valid) "API 可用" else "HTTP $code，未获得预期 API 响应")
        } catch (error: Exception) {
            ProbeResult(false, elapsed(), error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
