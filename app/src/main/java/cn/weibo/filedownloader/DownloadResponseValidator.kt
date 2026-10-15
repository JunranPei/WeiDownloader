package cn.weibo.filedownloader

internal object DownloadResponseValidator {
    fun isErrorContentType(contentType: String): Boolean =
        contentType.startsWith("text/", ignoreCase = true) ||
            contentType.contains("json", ignoreCase = true)

    fun isErrorBody(buffer: ByteArray, count: Int): Boolean {
        if (count <= 0) return true
        val prefix = String(buffer, 0, minOf(count, 128), Charsets.US_ASCII)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        return prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
            prefix.startsWith("{") || prefix.startsWith("[")
    }
}
