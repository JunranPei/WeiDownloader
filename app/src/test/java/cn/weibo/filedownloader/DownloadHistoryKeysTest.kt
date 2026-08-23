package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadHistoryKeysTest {
    @Test
    fun `signed url changes do not change the stable history key`() {
        val first = media("https://example.com/file.mp4?signature=first")
        val refreshed = media("https://example.com/file.mp4?signature=second")

        assertEquals(DownloadHistoryKeys.stable(first), DownloadHistoryKeys.stable(refreshed))
        assertNotEquals(
            DownloadHistoryKeys.legacyComposite(first),
            DownloadHistoryKeys.legacyComposite(refreshed),
        )
    }

    @Test
    fun `different source variants keep separate history entries`() {
        val original = media("https://example.com/original.mp4", variant = "original")
        val compressed = media("https://example.com/720.mp4", variant = "720p")

        assertNotEquals(DownloadHistoryKeys.stable(original), DownloadHistoryKeys.stable(compressed))
    }

    private fun media(url: String, variant: String = "original") = MediaItem(
        type = MediaType.VIDEO,
        url = url,
        quality = MediaQuality.PLATFORM_BEST,
        postId = "123",
        ordinal = 1,
        selectedVariantKey = variant,
    )
}
