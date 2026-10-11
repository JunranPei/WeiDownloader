package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaohongshuAuthEvidenceTest {
    @Test fun guestSessionDoesNotProveLogin() {
        assertEquals(false, XiaohongshuAuthEvidence.authenticated(JSONObject("""{"loggedIn":false,"guest":true,"userIdPresent":true}""")))
    }
    @Test fun authenticatedAccountMustBeNonGuest() {
        assertEquals(true, XiaohongshuAuthEvidence.authenticated(JSONObject("""{"loggedIn":true,"guest":false,"userIdPresent":true}""")))
        assertEquals(false, XiaohongshuAuthEvidence.authenticated(JSONObject("""{"loggedIn":true,"guest":true,"userIdPresent":true}""")))
    }
    @Test fun missingStateOrAccountIsUnverified() {
        assertNull(XiaohongshuAuthEvidence.authenticated(JSONObject()))
        assertNull(XiaohongshuAuthEvidence.authenticated(JSONObject("""{"loggedIn":true,"userIdPresent":true}""")))
        assertNull(XiaohongshuAuthEvidence.authenticated(JSONObject("""{"loggedIn":true,"guest":false,"userIdPresent":false}""")))
    }
}
