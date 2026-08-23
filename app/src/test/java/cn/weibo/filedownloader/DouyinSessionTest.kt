package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinSessionTest {
    @Test
    fun detectsLoginStatusCookie() {
        assertTrue(DouyinSession.isSignedIn("ttwid=value; LOGIN_STATUS=1"))
    }

    @Test
    fun detectsSessionCookies() {
        assertTrue(DouyinSession.isSignedIn("sid_guard=token%7C; passport_csrf_token=value"))
    }

    @Test
    fun ignoresAnonymousCookies() {
        assertFalse(DouyinSession.isSignedIn("ttwid=value; passport_csrf_token=value"))
    }
}
