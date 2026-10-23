package cn.weibo.filedownloader

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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

    fun isErrorBody(buffer: ByteArray, count: Int, contentType: String = ""): Boolean {
        if (count <= 0) return true
        val prefix = String(buffer, 0, minOf(count, buffer.size, 128), Charsets.US_ASCII)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        if (prefix.startsWith("<!doctype") || prefix.startsWith("<html") ||
            prefix.startsWith("{") || prefix.startsWith("[")) return true

        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime in setOf("image/svg+xml", "image/svg") ||
            prefix.startsWith("<svg") || prefix.startsWith("<?xml") && prefix.contains("<svg")) return false
        return isReadableText(buffer, count)
    }

    private fun isReadableText(buffer: ByteArray, count: Int): Boolean {
        val sampleSize = minOf(count, buffer.size, 128)
        if (sampleSize < 8) return false
        val sample = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer, 0, sampleSize))
                .toString()
        }.getOrNull()?.trimStart('\uFEFF', ' ', '\t', '\r', '\n') ?: return false
        if (sample.length < 8 || sample.all(Char::isWhitespace)) return false
        val readableChars = sample.count { !Character.isISOControl(it) }
        return readableChars >= sample.length * 0.9
    }
}
