package cn.weibo.filedownloader

import java.net.URI

/** Keep access tokens and nested redirect URLs out of diagnostics. */
internal fun xiaohongshuLogUrl(value: String?): String = runCatching {
    val uri = URI(value.orEmpty())
    "${uri.scheme.orEmpty()}://${uri.host.orEmpty()}${uri.path.orEmpty()}"
}.getOrDefault("<invalid-url>")
