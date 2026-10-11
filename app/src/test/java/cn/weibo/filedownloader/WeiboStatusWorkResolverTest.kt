package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeiboStatusWorkResolverTest {
    private fun work(height: Int): WeiboWorkData {
        val variant = MediaVariant("${height}p", "https://example.org/$height.mp4", height * 16 / 9, height)
        return WeiboWorkData("123", "title", "author", listOf(MediaItem(
            MediaType.VIDEO, variant.url, MediaQuality.PLAYLIST_MAX, "123", 1,
            variants = listOf(variant), selectedVariantKey = variant.key, videoObjectId = "1034:123",
        )))
    }

    @Test
    fun normalizationFailureAndEmptyDataContinueToNextProvider() {
        val resolved = WeiboStatusWorkResolver.resolve(
            listOf({ JSONObject().put("broken", true) }, { JSONObject() }, { JSONObject().put("height", 1080) }),
            { if (it.optBoolean("broken")) error("malformed media") else it.optInt("height").takeIf { h -> h > 0 }?.let(::work) },
        )
        assertEquals(work(1080), resolved)
    }

    @Test
    fun laterHigherQualityIsMergedAndEarlierTitleRetained() {
        val resolved = WeiboStatusWorkResolver.resolve(
            listOf({ JSONObject().put("height", 720) }, { JSONObject().put("height", 1080) }),
            { work(it.getInt("height")) },
        )!!
        assertEquals("https://example.org/1080.mp4", resolved.media.single().url)
        assertEquals(setOf("720p", "1080p"), resolved.media.single().variants.map { it.key }.toSet())
    }

    @Test
    fun laterFailureCannotDiscardPlayableStatus() {
        val resolved = WeiboStatusWorkResolver.resolve(
            listOf({ JSONObject() }, { error("network failure") }), { work(720) },
        )
        assertEquals(work(720), resolved)
    }

    @Test
    fun allFailedProvidersPreserveActualFailure() {
        val expected = IllegalStateException("unavailable")
        try {
            WeiboStatusWorkResolver.resolve(listOf({ throw expected }, { null }), { work(720) })
            fail("Expected the actual error")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }
}
