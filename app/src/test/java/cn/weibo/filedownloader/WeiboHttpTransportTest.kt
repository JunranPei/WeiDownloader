package cn.weibo.filedownloader

import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class WeiboHttpTransportTest {
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Response(val status: Int = 200, val location: String? = null, val body: String = "ok")

    // A real local HTTP fixture using only the same java.net APIs available to Android tests.
    private class Fixture(val handler: (Request, Int) -> Response) : AutoCloseable {
        private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val port get() = server.localPort
        val base get() = "http://127.0.0.1:$port"
        private val thread = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val first = reader.readLine().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = CharArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < body.size) {
                        val count = reader.read(body, offset, body.size - offset)
                        if (count < 0) error("Truncated request")
                        offset += count
                    }
                    val response = handler(Request(first[0], first[1], headers, String(body)), port)
                    val bytes = response.body.toByteArray()
                    val header = "HTTP/1.1 ${response.status} Response\r\n" +
                        response.location?.let { "Location: $it\r\n" }.orEmpty() +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                }
            } catch (error: Throwable) {
                if (!server.isClosed) failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        override fun close() {
            server.close()
            thread.join(4000)
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }

    @Test
    fun redirectsReplaceCookieAndXsrfWithDestinationSessionAndPreserve307PostBody() {
        val observed = mutableListOf<Request>()
        Fixture { request, port ->
            observed.add(request)
            if (request.path == "/start") Response(307, "http://localhost:$port/finish") else Response()
        }.use { fixture ->
            val connection = WeiboHttpTransport.open("${fixture.base}/start", { address ->
                if (URI(address).host == "localhost") "SUB=mobile; XSRF-TOKEN=new%20token" else "SUB=desktop; XSRF-TOKEN=old"
            }, "POST", "data=video".toByteArray(), mapOf("Cookie" to "wrong", "X-XSRF-TOKEN" to "wrong"))
            try { assertEquals("ok", connection.inputStream.bufferedReader().readText()) } finally { connection.disconnect() }
        }
        assertEquals("SUB=desktop; XSRF-TOKEN=old", observed[0].headers["cookie"])
        assertEquals("old", observed[0].headers["x-xsrf-token"])
        assertEquals("SUB=mobile; XSRF-TOKEN=new%20token", observed[1].headers["cookie"])
        assertEquals("new token", observed[1].headers["x-xsrf-token"])
        assertTrue(observed.all { it.method == "POST" && it.body == "data=video" })
    }

    @Test
    fun unauthenticatedRedirectDoesNotInheritCookiesAnd303DropsPostBody() {
        val observed = mutableListOf<Request>()
        Fixture { request, _ ->
            observed.add(request)
            if (request.path == "/start") Response(303, "/external") else Response()
        }.use { fixture ->
            val connection = WeiboHttpTransport.open("${fixture.base}/start", {
                if (it.endsWith("/start")) "SUB=old; XSRF-TOKEN=old" else ""
            }, "POST", "payload".toByteArray())
            try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
        }
        assertEquals("GET", observed[1].method)
        assertEquals("", observed[1].body)
        assertNull(observed[1].headers["cookie"])
        assertNull(observed[1].headers["x-xsrf-token"])
    }

    @Test
    fun redirectLoopIsBounded() {
        var requests = 0
        Fixture { _, _ -> requests++; Response(302, "/loop") }.use { fixture ->
            try {
                WeiboHttpTransport.open("${fixture.base}/loop", { "" })
                fail("Expected bounded redirect failure")
            } catch (error: java.io.IOException) {
                assertEquals("Too many redirects", error.message)
            }
        }
        assertEquals(9, requests)
    }
}
