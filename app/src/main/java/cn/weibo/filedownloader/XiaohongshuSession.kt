package cn.weibo.filedownloader

import android.webkit.CookieManager

internal object XiaohongshuSession {
    private val cookieUrls = listOf(
        "https://www.xiaohongshu.com",
        "https://xiaohongshu.com",
        "https://edith.xiaohongshu.com",
        "https://www.rednote.com",
        "https://rednote.com",
    )

    fun cookieHeader(): String = cookieUrls.mapNotNull(CookieManager.getInstance()::getCookie)
        .distinct()
        .joinToString("; ")

    fun isSignedIn(): Boolean {
        val names = cookieHeader()
            .split(';')
            .asSequence()
            .map { it.substringBefore('=').trim().lowercase() }
            .toSet()
        return names.any { it in AUTH_COOKIE_NAMES }
    }

    private val AUTH_COOKIE_NAMES = setOf(
        "web_session",
        "access-token",
        "customer-client-sso-sid",
    )
}
