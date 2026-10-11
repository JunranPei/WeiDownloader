package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeiboAuthEvidenceTest {
    @Test
    fun onlyExplicitServerAuthenticationConfirmsLogin() {
        assertEquals(true, WeiboAuthEvidence.authenticated(JSONObject("""{"ok":1,"data":{"login":true}}""")))
        assertEquals(false, WeiboAuthEvidence.authenticated(JSONObject("""{"ok":1,"data":{"login":false}}""")))
        assertNull(WeiboAuthEvidence.authenticated(JSONObject("""{"id":"public-profile","SUB":"exists"}""")))
        assertNull(WeiboAuthEvidence.authenticated(JSONObject("""{"ok":0,"data":{"login":true}}""")))
        assertNull(WeiboAuthEvidence.authenticated(JSONObject("""{"data":{"login":"true"}}""")))
    }

    @Test
    fun changedCookiesOrExpiredEvidenceCannotRemainSignedIn() {
        val session = WeiboVerifiedSession()
        session.record("original", true, 100_000)
        assertEquals(true, session.authenticated("original", 100_001))
        assertNull(session.authenticated("changed", 100_001))
        assertNull(session.authenticated("original", 160_001))
        assertNull(session.authenticated("original", 99_999))
    }

    @Test
    fun FailedOrUnauthenticatedRefreshDoesNotReusePreviousSuccess() {
        val session = WeiboVerifiedSession()
        session.record("session", true, 100)
        session.record("session", null, 200)
        assertNull(session.authenticated("session", 201))
        session.record("session", false, 300)
        assertEquals(false, session.authenticated("session", 301))
    }
}
