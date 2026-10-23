package cn.weibo.filedownloader

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.Locale

/** Follows bounded HTTP redirects without carrying origin credentials to another origin. */
internal object SafeHttpRedirects {
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(300, 301, 302, 303, 307, 308)
    private val crossOriginSafeHeaders = setOf(
        "accept",
        "accept-encoding",
        "accept-language",
        "cache-control",
        "pragma",
        "user-agent",
    )

    fun openConnection(
        address: String,
        allowRedirect: (URL) -> Boolean = { true },
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        configure: HttpURLConnection.() -> Unit,
    ): HttpURLConnection {
        var currentUrl = URL(address)
        validateTarget(currentUrl, allowRedirect)
        val credentialOrigin = currentUrl
        var redirects = 0
        val visited = hashSetOf(currentUrl.toExternalForm())
        var connection = newConnection(currentUrl, connectionFactory, configure)

        while (true) {
            val status = connection.responseCode
            if (status !in redirectCodes) return connection
            val location = connection.getHeaderField("Location")
                ?: return connection
            if (redirects >= MAX_REDIRECTS) {
                connection.disconnect()
                throw ProtocolException("Too many HTTP redirects")
            }
            val nextUrl = try {
                URL(currentUrl, location)
            } catch (error: Exception) {
                connection.disconnect()
                throw ProtocolException("Invalid HTTP redirect location")
            }
            try {
                validateTarget(nextUrl, allowRedirect)
            } catch (error: ProtocolException) {
                connection.disconnect()
                throw error
            }
            val scheme = nextUrl.protocol.lowercase(Locale.ROOT)
            if (currentUrl.protocol.equals("https", ignoreCase = true) && scheme != "https") {
                connection.disconnect()
                throw ProtocolException("Refusing HTTPS downgrade redirect")
            }
            if (!visited.add(nextUrl.toExternalForm())) {
                connection.disconnect()
                throw ProtocolException("HTTP redirect loop")
            }

            val preserveOriginHeaders = sameOrigin(credentialOrigin, nextUrl)
            connection.disconnect()
            connection = newConnection(nextUrl, connectionFactory, configure)
            if (!preserveOriginHeaders) {
                connection.requestProperties.keys
                    .filter { it.lowercase(Locale.ROOT) !in crossOriginSafeHeaders }
                    .forEach { connection.setRequestProperty(it, "") }
            }
            currentUrl = nextUrl
            redirects++
        }
    }

    private fun newConnection(
        url: URL,
        connectionFactory: (URL) -> HttpURLConnection,
        configure: HttpURLConnection.() -> Unit,
    ) = connectionFactory(url).apply {
            configure()
            instanceFollowRedirects = false
        }

    private fun sameOrigin(first: URL, second: URL): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int = when {
        url.port >= 0 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun validateTarget(url: URL, allowTarget: (URL) -> Boolean) {
        val scheme = url.protocol.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || url.host.isBlank() || url.userInfo != null) {
            throw ProtocolException("Unsupported HTTP request target")
        }
        if (url.port != -1 && url.port !in 1..65535) {
            throw ProtocolException("Invalid HTTP request port")
        }
        if (!allowTarget(url)) throw ProtocolException("HTTP request target is not allowed")
    }
}
