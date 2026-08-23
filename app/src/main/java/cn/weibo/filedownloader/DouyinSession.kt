package cn.weibo.filedownloader

import android.webkit.CookieManager

object DouyinSession {
    private val cookieUrls = listOf(
        "https://www.douyin.com",
        "https://sso.douyin.com",
        "https://login.douyin.com",
    )

    fun cookieHeader(): String {
        val manager = CookieManager.getInstance()
        return cookieUrls
            .mapNotNull(manager::getCookie)
            .flatMap { it.split(';') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.substringBefore('=').trim().lowercase() }
            .joinToString("; ")
    }

    fun isSignedIn(): Boolean = isSignedIn(cookieHeader())

    internal fun isSignedIn(cookieHeader: String): Boolean {
        val cookies = cookieHeader.split(';').mapNotNull { value ->
            val parts = value.trim().split('=', limit = 2)
            parts.firstOrNull()?.trim()?.lowercase()?.takeIf(String::isNotBlank)
                ?.let { it to parts.getOrNull(1).orEmpty().trim() }
        }.toMap()
        return cookies["login_status"] == "1" ||
            listOf("sessionid", "sessionid_ss", "sid_guard").any { cookies[it].isNullOrBlank().not() }
    }
}
