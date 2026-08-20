package cn.weibo.filedownloader

import android.webkit.CookieManager

object WeiboSession {
    private val cookieUrls = listOf(
        "https://weibo.com",
        "https://m.weibo.cn",
        "https://weibo.cn",
        "https://passport.weibo.com",
    )

    fun cookieHeader(): String {
        val manager = CookieManager.getInstance()
        return cookieUrls
            .mapNotNull { manager.getCookie(it) }
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.substringBefore('=').trim().lowercase() }
            .joinToString("; ")
    }

    fun isSignedIn(): Boolean = cookieHeader()
        .split(';')
        .any { cookie ->
            val (name, value) = cookie.trim().split('=', limit = 2).let {
                it.firstOrNull().orEmpty() to it.getOrNull(1).orEmpty()
            }
            name.equals("SUB", ignoreCase = true) && value.isNotBlank()
        }
}
