package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test

class MediaMetadataEnrichmentTest {
    @Test fun probingTheSelectedVideoKeepsAllEncodingsAndFallbacks() {
        val original = MediaItem(MediaType.VIDEO, "https://video.example/main.mp4", MediaQuality.HD_BEST, "post", 2,
            variants = listOf(MediaVariant("original", "https://video.example/main.mp4"),
                MediaVariant("720p", "https://video.example/720.mp4", 720, 1280)),
            selectedVariantKey = "original", fallbackUrls = listOf("https://video.example/backup.mp4"))
        val enriched = original.withProbedMetadata(1080, 1920, 2_000_000)
        assertEquals(original.stableId, enriched.stableId)
        assertEquals(original.variants.map { it.url }, enriched.variants.map { it.url })
        assertEquals(original.fallbackUrls, enriched.fallbackUrls)
        assertEquals(original.selectedVariantKey, enriched.selectedVariantKey)
        assertEquals(1920, enriched.variants.first().height)
        assertTrue(enriched.variants.first().dimensionsVerified)
    }
}
