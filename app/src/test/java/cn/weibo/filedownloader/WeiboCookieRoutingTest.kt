package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class WeiboCookieRoutingTest {
    @Test
    fun equalCookieNamesKeepTheirOwnDomainValues() {
        val desktop = "https://weibo.com/ajax/statuses/show?id=123"
        val mobile = "https://m.weibo.cn/statuses/show?id=123"
        val lookup: (String) -> String? = { address ->
            when (address) {
                desktop -> "SUB=desktop-session; XSRF-TOKEN=desktop-token"
                mobile -> "SUB=mobile-session; XSRF-TOKEN=mobile-token"
                else -> error("Unexpected lookup")
            }
        }
        assertEquals("SUB=desktop-session; XSRF-TOKEN=desktop-token", WeiboCookieRouting.headerFor(desktop, lookup))
        assertEquals("SUB=mobile-session; XSRF-TOKEN=mobile-token", WeiboCookieRouting.headerFor(mobile, lookup))
    }

    @Test
    fun redirectToDifferentDomainDoesNotReuseThePreviousCookie() {
        val lookup: (String) -> String? = { if (it.startsWith("https://t.cn/")) null else "SUB=weibo-session" }
        assertEquals("", WeiboCookieRouting.headerFor("https://t.cn/example", lookup))
        assertEquals("SUB=weibo-session", WeiboCookieRouting.headerFor("https://weibo.com/123/example", lookup))
    }

    @Test
    fun unrelatedOrMalformedTargetsNeverReadSessionCookies() {
        listOf("https://weibo.com.example.org/", "https://example.org/", "file:///weibo.com", "invalid URL").forEach {
            assertEquals("", WeiboCookieRouting.headerFor(it) { error("Must not read cookies") })
        }
    }
}
