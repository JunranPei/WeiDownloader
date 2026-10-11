package cn.weibo.filedownloader

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.net.HttpURLConnection

internal data class WeiboMediaProbeResult(
    val width: Int,
    val height: Int,
    val bitrate: Long,
)

internal interface WeiboMediaMetadataProbe {
    fun imageDimensions(url: String): Pair<Int, Int>?
    fun mediaMetadata(url: String): WeiboMediaProbeResult?
}

/** Measures fields omitted by some Weibo responses without changing parsing rules. */
internal class WeiboRemoteMediaMetadataProbe : WeiboMediaMetadataProbe {
    override fun imageDimensions(url: String): Pair<Int, Int>? {
        var connection: HttpURLConnection? = null
        return try {
            val active = WeiboSession.openConnection(url,
                headers = mapOf("Referer" to "https://weibo.com/", "Range" to "bytes=0-262143"),
                connectTimeout = 8_000, readTimeout = 8_000).also { connection = it }
            if (active.responseCode !in 200..299) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BufferedInputStream(active.inputStream).use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    override fun mediaMetadata(url: String): WeiboMediaProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            val connection = WeiboSession.openConnection(url,
                headers = mapOf("Referer" to "https://weibo.com/", "Range" to "bytes=0-0"),
                connectTimeout = 8_000, readTimeout = 8_000)
            val resolvedUrl = try {
                if (connection.responseCode !in 200..299) return null
                connection.url.toString()
            } finally {
                connection.disconnect()
            }
            retriever.setDataSource(
                resolvedUrl,
                mapOf("User-Agent" to WeiboHttpTransport.USER_AGENT, "Referer" to "https://weibo.com/",
                    "Cookie" to WeiboSession.cookieHeader(resolvedUrl)),
            )
            WeiboMediaProbeResult(
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

}
