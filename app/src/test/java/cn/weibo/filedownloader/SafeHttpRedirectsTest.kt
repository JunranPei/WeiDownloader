package cn.weibo.filedownloader

import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHttpRedirectsTest {
    @Test fun stripsOriginCredentialsAndCustomHeadersAfterCrossOriginRedirect() {
        val redirected = AtomicReference<FakeConnection>()
        val result = SafeHttpRedirects.openConnection(
            "https://douyin.test/start",
            connectionFactory = { url ->
                if (url.host == "douyin.test") FakeConnection(url, 302, "https://cdn.test/media")
                else FakeConnection(url, 200).also(redirected::set)
            },
        ) {
            setRequestProperty("Cookie", "session=secret")
            setRequestProperty("Authorization", "Bearer secret")
            setRequestProperty("Referer", "https://douyin.test/private")
            setRequestProperty("X-Api-Key", "secret")
            setRequestProperty("User-Agent", "test-agent")
            setRequestProperty("Accept", "image/*")
        }

        assertEquals(200, result.responseCode)
        val connection = requireNotNull(redirected.get())
        val headers = connection.requestProperties
        assertFalse(connection.getRequestProperty("Cookie").orEmpty().contains("secret"))
        assertFalse(connection.getRequestProperty("Authorization").orEmpty().contains("secret"))
        assertFalse(connection.getRequestProperty("Referer").orEmpty().contains("private"))
        assertFalse(connection.getRequestProperty("X-Api-Key").orEmpty().contains("secret"))
        assertEquals("test-agent", headers.entries.single { it.key.equals("User-Agent", true) }.value.single())
        assertEquals("image/*", headers.entries.single { it.key.equals("Accept", true) }.value.single())
    }

    @Test fun preservesCredentialsForSameOriginRedirect() {
        val redirected = AtomicReference<FakeConnection>()
        SafeHttpRedirects.openConnection(
            "https://douyin.test/start",
            connectionFactory = { url ->
                if (url.path == "/start") FakeConnection(url, 302, "/media")
                else FakeConnection(url, 200).also(redirected::set)
            },
        ) {
            setRequestProperty("Cookie", "session=secret")
        }

        val headers = requireNotNull(redirected.get()).requestProperties
        assertEquals("session=secret", headers.entries.single { it.key.equals("Cookie", true) }.value.single())
    }

    @Test fun rejectsOffDomainRedirectWhenTheCallerRequiresDouyinHosts() {
        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection(
                "https://v.douyin.com/share",
                allowRedirect = { it.host.endsWith(".douyin.com") || it.host == "douyin.com" },
                connectionFactory = { FakeConnection(it, 302, "https://attacker.test/collect") },
            ) {}
        }
    }

    @Test fun rejectsUnsafeInitialTargetsBeforeOpeningAConnection() {
        var opened = false
        val factory: (URL) -> HttpURLConnection = { url ->
            opened = true
            FakeConnection(url, 200)
        }

        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection("https://name:secret@douyin.test/start", connectionFactory = factory) {}
        }
        assertFalse(opened)

        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection("file:/private/image.jpg", connectionFactory = factory) {}
        }
        assertFalse(opened)

        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection(
                "https://attacker.test/start",
                allowRedirect = { it.host.endsWith(".douyin.com") || it.host == "douyin.com" },
                connectionFactory = factory,
            ) {}
        }
        assertFalse(opened)
    }

    @Test fun rejectsHttpsDowngradeRedirect() {
        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection(
                "https://douyin.test/start",
                connectionFactory = {
                    if (it.protocol == "https") FakeConnection(it, 302, "http://douyin.test/final")
                    else FakeConnection(it, 200)
                },
            ) {}
        }
    }

    @Test fun rejectsRedirectLoopsAndExcessiveHopsButAllowsFiveRedirects() {
        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection(
                "https://douyin.test/start",
                connectionFactory = { FakeConnection(it, 302, "/start") },
            ) {}
        }

        var sixthHopReached = false
        assertThrows(ProtocolException::class.java) {
            SafeHttpRedirects.openConnection(
                "https://douyin.test/0",
                connectionFactory = {
                    val index = it.path.substringAfterLast('/').toInt()
                    if (index == 6) sixthHopReached = true
                    FakeConnection(it, if (index == 6) 200 else 302, "/${index + 1}")
                },
            ) {}
        }
        assertFalse(sixthHopReached)

        val final = SafeHttpRedirects.openConnection(
            "https://douyin.test/0",
            connectionFactory = {
                val index = it.path.substringAfterLast('/').toInt()
                if (index == 5) FakeConnection(it, 200)
                else FakeConnection(it, 302, "/${index + 1}")
            },
        ) {}
        assertEquals(200, final.responseCode)
        assertNull(final.getHeaderField("Location"))
        assertTrue(final.url.path == "/5")
    }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? =
            if (name.equals("Location", ignoreCase = true)) location else null
    }
}
