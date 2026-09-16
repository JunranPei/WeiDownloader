package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {
    @Test fun videoNeverUsesImageExtension() = assertEquals(
        "video_123_01.mp4", media(MediaType.VIDEO, "https://example.com/video-poster.jpg").suggestedFileName(),
    )

    @Test fun livePhotoNeverUsesImageExtension() = assertEquals(
        "live_123_01.mp4", media(MediaType.LIVE_PHOTO, "https://example.com/live-cover.webp").suggestedFileName(),
    )

    @Test fun compatibleVideoExtensionIsPreserved() = assertEquals(
        "video_123_01.mov", media(MediaType.VIDEO, "https://example.com/source.mov").suggestedFileName(),
    )

    @Test fun imageNeverUsesVideoExtension() = assertEquals(
        "image_123_01.jpg", media(MediaType.IMAGE, "https://example.com/source.mp4").suggestedFileName(),
    )

    @Test fun imageFormatInTransformQueryIsUsedAsExtension() = assertEquals(
        "image_123_01.png",
        media(MediaType.IMAGE, "https://example.com/source?imageView2/format/png").suggestedFileName(),
    )

    @Test fun imagePathIsNotAcceptedAsVideoPath() {
        assertTrue(MediaFileTypes.isKnownImagePath("/poster.jpg"))
        assertFalse(MediaFileTypes.isKnownVideoPath("/poster.jpg"))
    }

    @Test fun measuredVideoResolutionWinsAfterHydration() {
        val low = MediaVariant("variant 1", "https://example.com/low.mp4", 640, 360, 500_000)
        val high = MediaVariant("variant 2", "https://example.com/high.mp4", 1920, 1080, 2_000_000)
        assertEquals(high, MediaVariantSelector.rank(MediaType.VIDEO, listOf(low, high)).first())
    }

    @Test fun inferredHdWinsWhenMetadataCannotBeMeasured() {
        val sd = MediaVariant("mp4 sd", "https://example.com/a")
        val hd = MediaVariant("mp4 hd", "https://example.com/b")
        assertEquals(hd, MediaVariantSelector.rank(MediaType.VIDEO, listOf(sd, hd)).first())
    }

    private fun media(type: MediaType, url: String) = MediaItem(type, url, "test", "123", 1)
}
