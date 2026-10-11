package cn.weibo.filedownloader

import java.net.URI

/** Keep each WebView session on the domain that issued it. */
internal object WeiboCookieRouting {
    fun headerFor(address: String, lookup: (String) -> String?): String {
        val uri = runCatching { URI(address) }.getOrNull() ?: return ""
        val host = uri.host?.lowercase() ?: return ""
        if (uri.scheme != "https" && uri.scheme != "http") return ""
        if (host != "weibo.com" && !host.endsWith(".weibo.com") &&
            host != "weibo.cn" && !host.endsWith(".weibo.cn") && host != "t.cn") return ""
        return lookup(address).orEmpty()
    }
}
