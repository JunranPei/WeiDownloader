package cn.weibo.filedownloader

internal object DownloadResponseValidator {
    // Unknown CDN MIME types remain usable. Reject only positive evidence
    // that the response belongs to a different kind of media.
    fun isWrongMediaType(contentType: String, expected: MediaType?): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when (expected) {
            MediaType.IMAGE -> mime.startsWith("video/") || mime.startsWith("audio/")
            MediaType.VIDEO, MediaType.LIVE_PHOTO -> mime.startsWith("image/") || mime.startsWith("audio/")
            // DASH audio is sometimes served as video/mp4, so do not reject it.
            MediaType.AUDIO -> mime.startsWith("image/")
            null -> false
        }
    }

    fun isTruncated(expectedBytes: Long, receivedBytes: Long): Boolean =
        expectedBytes >= 0 && receivedBytes != expectedBytes

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
