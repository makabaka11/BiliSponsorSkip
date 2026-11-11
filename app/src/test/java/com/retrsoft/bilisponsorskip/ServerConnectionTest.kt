package com.retrsoft.bilisponsorskip

import java.net.ServerSocket
import java.net.SocketException
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ServerConnectionTest {
    @Test
    fun validatesAndNormalizesServerAddresses() {
        assertEquals("https://example.com/base", ServerConnection.normalize(" https://example.com/base/ "))
        assertEquals("http://127.0.0.1:9876", ServerConnection.normalize("http://127.0.0.1:9876"))
        listOf("", "example.com", "ftp://example.com", "https://user:pass@example.com",
            "https://example.com?q=1", "https://example.com#part", "http://example.com:99999").forEach {
            assertNull(it, ServerConnection.normalize(it))
        }
    }

    @Test
    fun measuresCompleteApiResponseAndRejectsHtmlErrorsAndRedirects() {
        withServer { server, base ->
            var code = 200
            var body = "[]"
            server.respond = { _, _ -> Response(code, body, 90) }
            val success = ServerConnection.probe(base)
            assertTrue(success.message, success.available)
            assertTrue("Must include response body wait", success.elapsedMs >= 80)
            body = "<html>Gateway error</html>"
            assertFalse(ServerConnection.probe(base).available)
            code = 404
            assertFalse(ServerConnection.probe(base).available)
            body = "Not Found"
            assertTrue(ServerConnection.probe(base).available)
            code = 503
            assertFalse(ServerConnection.probe(base).available)
            code = 302
            assertFalse(ServerConnection.probe(base).available)
        }
    }

    @Test
    fun routesQueriesAndMutationsToSelectedServerIncludingBasePath() {
        withServer { server, base ->
            val paths = CopyOnWriteArrayList<String>()
            server.respond = { method, path ->
                paths += path.substringBefore('?')
                val response = when {
                    path.contains("getViewsForUser") -> "{\"viewCount\":1}"
                    path.contains("getSavedTimeForUser") -> "{\"timeSaved\":2}"
                    method == "GET" -> "[]"
                    else -> "OK"
                }
                Response(200, response)
            }
            val client = SponsorBlockClient { "$base/custom/" }
            assertTrue(client.getSponsorSegments("BVtest", "1") is SponsorBlockClient.Result.Success)
            assertTrue(client.vote("uuid", "private-id", 1).successful)
            assertTrue(client.setUsername("private-id", "name").successful)
            assertTrue(client.submitSegment("BVtest", "1", "private-id", "sponsor", 0, 1000, 5000).successful)
            assertTrue(client.getUserContributionStats("private-id") is SponsorBlockClient.UserStatsResult.Success)
            assertEquals(6, paths.size)
            assertTrue(paths.all { it.startsWith("/custom/api/") })
        }
    }

    private data class Response(val code: Int, val body: String, val delayMs: Long = 0)

    private class TestServer : Closeable {
        private val socket = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}"
        @Volatile var respond: (String, String) -> Response = { _, _ -> Response(500, "") }
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine().split(' ')
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val result = respond(request[0], request[1])
                        val bytes = result.body.toByteArray()
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 ${result.code} Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        Thread.sleep(result.delayMs)
                        output.write(bytes)
                        output.flush()
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) throw error
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() { socket.close(); thread.join(2000) }
    }

    private fun withServer(block: (TestServer, String) -> Unit) {
        TestServer().use { block(it, it.base) }
    }
}
