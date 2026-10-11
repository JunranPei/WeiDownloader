package cn.weibo.filedownloader

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Rebuild credentials for every redirect instead of forwarding the previous host's headers. */
internal object WeiboHttpTransport {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

    fun open(
        address: String,
        cookieFor: (String) -> String,
        method: String = "GET",
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeout: Int = 20_000,
        readTimeout: Int = 30_000,
    ): HttpURLConnection {
        var current = URL(address)
        var verb = method
        var payload = body
        repeat(9) { hop ->
            if (current.protocol !in listOf("http", "https")) throw IOException("Unsupported redirect protocol")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = verb
                this.connectTimeout = connectTimeout
                this.readTimeout = readTimeout
                setRequestProperty("User-Agent", USER_AGENT)
                headers.filterKeys { !it.equals("Cookie", true) && !it.equals("X-XSRF-TOKEN", true) }
                    .forEach { (key, value) -> setRequestProperty(key, value) }
                val cookie = cookieFor(current.toString())
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                xsrfToken(cookie)?.let { setRequestProperty("X-XSRF-TOKEN", it) }
                if (payload != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(payload!!.size)
                }
            }
            try {
                payload?.let { bytes -> connection.outputStream.use { it.write(bytes) } }
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (status !in listOf(301, 302, 303, 307, 308) || location.isNullOrBlank()) return connection
                if (hop == 8) throw IOException("Too many redirects")
                val next = URL(current, location)
                if (current.protocol == "https" && next.protocol != "https") throw IOException("Insecure redirect")
                if (status == 303 && verb != "HEAD" || status in listOf(301, 302) && verb == "POST") {
                    verb = "GET"
                    payload = null
                }
                current = next
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many redirects")
    }

    private fun xsrfToken(cookie: String): String? = Regex("(?:^|;\\s*)XSRF-TOKEN=([^;]+)")
        .find(cookie)?.groupValues?.get(1)?.let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
}
