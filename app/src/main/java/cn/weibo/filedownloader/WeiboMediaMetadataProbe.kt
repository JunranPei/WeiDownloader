package cn.weibo.filedownloader

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

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
internal class WeiboRemoteMediaMetadataProbe(
    private val cookie: String,
) : WeiboMediaMetadataProbe {
    override fun imageDimensions(url: String): Pair<Int, Int>? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Referer", "https://weibo.com/")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Range", "bytes=0-262143")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BufferedInputStream(connection.inputStream).use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    override fun mediaMetadata(url: String): WeiboMediaProbeResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(
                url,
                mapOf("User-Agent" to AGENT, "Referer" to "https://weibo.com/", "Cookie" to cookie),
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

    private companion object {
        const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
    }
}
