package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WeiboApiResponseTest {
    @Test
    fun desktopPermissionResponseUsesAppLanguageInsteadOfChineseServerMessage() {
        val response = JSONObject("""{"ok":0,"message":"暂无查看权限","error_code":20112}""")
        assertEquals("This post is unavailable.", WeiboApiResponse.errorMessage(response, "generic error", "This post is unavailable."))
    }

    @Test
    fun mobilePermissionResponseAlsoUsesAppLanguage() {
        val response = JSONObject("""{"ok":0,"msg":"暂无查看权限","errno":"20112"}""")
        assertEquals("This post is unavailable.", WeiboApiResponse.errorMessage(response, "generic error", "This post is unavailable."))
    }

    @Test
    fun nullComponentIsNeverReportedAsSuccess() {
        val response = JSONObject("""{"code":"100000","msg":"succ","data":{"Component_Play_Playinfo":null}}""")
        assertEquals("no media", WeiboApiResponse.errorMessage(response, "no media", "unavailable"))
    }

    @Test
    fun unknownChineseServerErrorUsesLocalizedFallback() {
        val response = JSONObject("""{"ok":0,"msg":"系统错误","errno":"99999"}""")
        assertEquals("Could not load this post.", WeiboApiResponse.errorMessage(response, "Could not load this post.", "unavailable"))
    }

    @Test
    fun chineseAppKeepsItsLocalizedUnavailableText() {
        val response = JSONObject("""{"error_code":20112,"message":"unavailable"}""")
        assertEquals("微博无法访问。", WeiboApiResponse.errorMessage(response, "读取失败。", "微博无法访问。"))
    }
}
