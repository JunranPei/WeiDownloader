package cn.weibo.filedownloader

import android.webkit.CookieManager

object BilibiliSession {
    private val cookieUrls = listOf(
        "https://www.bilibili.com",
        "https://api.bilibili.com",
        "https://passport.bilibili.com",
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

    fun isSignedIn(): Boolean = cookieHeader()
        .split(';')
        .map { it.trim().split('=', limit = 2) }
        .any { parts ->
            parts.firstOrNull().equals("SESSDATA", ignoreCase = true) &&
                parts.getOrNull(1).isNullOrBlank().not()
        }
}
