package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test

class DownloadLocatorTest {
    private fun item(id: String = "attachment", url: String = "https://cdn.example/video.mp4?sign=old", ordinal: Int = 1) =
        MediaItem(MediaType.VIDEO, url, MediaQuality.HD_BEST, "post", ordinal,
            sourceId = id, selectedVariantKey = "1080p h264",
            variants = listOf(MediaVariant("1080p h264", url, 1080, 1920, audioUrl = "https://cdn.example/audio?sign=old")))
    private fun post(vararg items: MediaItem) = WeiboPost("post", "title", "author", "https://www.douyin.com/video/post", items.toList())
    private fun locator(item: MediaItem, cover: Boolean = false) = DownloadLocator.create(Platform.DOUYIN, post(item), item, cover)

    @Test fun persistedIntentContainsIdentityAndQualityWithoutDeliveryUrlsOrCookies() {
        val item = item()
        val original = locator(item)
        val encoded = original.encode()
        assertEquals(original, DownloadLocator.decode(encoded))
        assertFalse(encoded.contains("cdn.example"))
        assertFalse(encoded.contains("sign=old"))
        assertFalse(encoded.contains("cookie"))
    }

    @Test fun reorderingAttachmentsNeverChangesTheSelectedContent() {
        val original = item()
        val fresh = item(url = "https://new-cdn.example/new.mp4?sign=new", ordinal = 2)
        val selected = locator(original).select(post(item("other", ordinal = 1), fresh))
        assertEquals(fresh.url, selected.url)
        assertEquals("attachment", selected.sourceId)
    }

    @Test fun aMissingAttachmentDoesNotFallBackToItsOldPosition() {
        try {
            locator(item()).select(post(item("replacement")))
            fail("Must reject a different attachment at the same ordinal")
        } catch (_: DownloadIdentityException) { }
    }

    @Test fun neverAcceptsAnotherPostOrAnAmbiguousAttachment() {
        val locator = locator(item())
        for (candidate in listOf(post(item()).copy(id = "other"), post(item(), item(ordinal = 2)))) {
            try { locator.select(candidate); fail("Must reject mismatched identity") }
            catch (_: DownloadIdentityException) { }
        }
    }

    @Test fun refreshPreservesResolutionCodecAndFreshDashAudio() {
        val original = item()
        val newUrl = "https://cdn.example/video.mp4?sign=new"
        val fresh = original.copy(url = newUrl, audioUrl = "https://cdn.example/audio?sign=new",
            variants = listOf(
                MediaVariant("1080p h265", "https://cdn.example/hevc.mp4", 1080, 1920),
                MediaVariant("1080p h264 new bitrate", newUrl, 1080, 1920, audioUrl = "https://cdn.example/audio?sign=new"),
                MediaVariant("720p h264", "https://cdn.example/720.mp4", 720, 1280)),
            fallbackUrls = listOf("https://cdn.example/720.mp4"))
        val chosen = locator(original).select(post(fresh))
        assertEquals(newUrl, chosen.url)
        assertEquals("https://cdn.example/audio?sign=new", chosen.audioUrl)
        assertTrue(chosen.fallbackUrls.isEmpty())
    }

    @Test fun unavailableQualityIsNotSilentlyDowngraded() {
        val fresh = item().copy(variants = listOf(MediaVariant("720p h264", "https://cdn.example/720.mp4", 720, 1280)))
        try { locator(item()).select(post(fresh)); fail("Must preserve the requested quality") }
        catch (_: DownloadIdentityException) { }
    }

    @Test fun originalIsNotReplacedByAnEqualResolutionWebPreview() {
        val original = item().copy(type = MediaType.IMAGE, variants = listOf(MediaVariant("original", item().url, 1080, 1920)))
        val preview = original.copy(variants = listOf(MediaVariant("web", "https://cdn.example/preview.jpg", 1080, 1920)))
        try { locator(original).select(post(preview)); fail("Must preserve original-source intent") }
        catch (_: DownloadIdentityException) { }
    }

    @Test fun coverIsRenewedFromTheSameLivePhotoWithOnlyImageSources() {
        val live = item().copy(type = MediaType.LIVE_PHOTO, coverUrl = "https://image.example/cover.jpg?old")
        val fresh = live.copy(coverUrl = "https://image.example/cover.jpg?new",
            coverFallbackUrls = listOf("https://image.example/backup.jpg?new"))
        val chosen = locator(live, true).select(post(fresh))
        assertEquals(MediaType.IMAGE, chosen.type)
        assertEquals(fresh.coverUrl, chosen.url)
        assertEquals(fresh.coverFallbackUrls, chosen.fallbackUrls)
        assertTrue(chosen.audioUrl.isEmpty())
    }

    @Test fun legacySignedUrlsCanMatchTheSameResourceAcrossCdnHosts() {
        val old = item(id = "")
        val fresh = old.copy(url = "https://other-cdn.example/video.mp4?sign=new",
            variants = listOf(MediaVariant("1080p h264", "https://other-cdn.example/video.mp4?sign=new", 1080, 1920)))
        assertEquals(fresh.url, locator(old).select(post(fresh)).url)
    }

    @Test fun identicalKeysStillPreserveExplicitCodecMetadata() {
        val original = item().copy(variants = listOf(MediaVariant("masterUrl", item().url, 1080, 1920, codec = "h264")))
        val fresh = original.copy(variants = listOf(
            MediaVariant("masterUrl", "https://cdn.example/265.mp4", 1080, 1920, codec = "h265"),
            MediaVariant("masterUrl", "https://cdn.example/264.mp4?new", 1080, 1920, codec = "h264")))
        assertEquals("https://cdn.example/264.mp4?new", locator(original).select(post(fresh)).url)
    }

    @Test fun weiboLivePhotoPlaybackUrlsRemainDistinctAcrossRenewal() {
        fun live(fid: String, ordinal: Int) = MediaItem(
            MediaType.LIVE_PHOTO,
            "https://video.weibo.com/media/play?livephoto=https%3A%2F%2Flivephoto.us.sinaimg.cn%2F$fid.mov",
            MediaQuality.LIVE_ORIGINAL,
            "post",
            ordinal,
        )
        val first = live("first", 1)
        val second = live("second", 2)
        val target = DownloadLocator.create(Platform.WEIBO, post(first, second), second, false)
        val fresh = second.copy(url = second.url)
        assertEquals(fresh.url, target.select(post(first, fresh)).url)
    }
}
