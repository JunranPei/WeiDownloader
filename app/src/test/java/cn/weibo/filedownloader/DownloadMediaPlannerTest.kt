package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMediaPlannerTest {
    @Test fun coverUsesOnlyItsOwnFallbackSources() {
        val clip = livePhoto.copy(
            fallbackUrls = listOf("https://video.example/backup.mp4"),
            coverFallbackUrls = listOf("https://image.example/backup.jpg"),
        )
        assertEquals(clip.coverFallbackUrls, DownloadMediaPlanner.expand(clip, true)[1].fallbackUrls)
    }
    @Test fun liveCoverNeverInheritsVideoSourcesOrAudioTracks() {
        val clip = livePhoto.copy(
            variants = listOf(MediaVariant("1080p", livePhoto.url, 1080, 1920)),
            selectedVariantKey = "1080p",
            fallbackUrls = listOf("https://v3-web.douyinvod.com/backup.mp4"),
            audioUrl = "https://v3-web.douyinvod.com/audio.m4a",
        )
        val cover = DownloadMediaPlanner.expand(clip, includeLiveCover = true)[1]
        assertTrue(cover.fallbackUrls.isEmpty())
        assertTrue(cover.variants.none { it.url == clip.url })
        assertEquals("", cover.audioUrl)
        assertTrue(cover.selectedVariantKey != "1080p")
    }

    private val livePhoto = MediaItem(
        type = MediaType.LIVE_PHOTO,
        url = "https://v3-web.douyinvod.com/live.mp4",
        quality = MediaQuality.LIVE_ORIGINAL,
        postId = "post",
        ordinal = 1,
        coverUrl = "https://p3-sign.douyinpic.com/live-cover.jpeg",
    )

    @Test fun excludesLivePhotoCoverWhenSettingIsOff() {
        val downloads = DownloadMediaPlanner.expand(livePhoto, includeLiveCover = false)

        assertEquals(listOf(livePhoto.url), downloads.map { it.url })
    }

    @Test fun includesLivePhotoCoverWhenSettingIsOn() {
        val downloads = DownloadMediaPlanner.expand(livePhoto, includeLiveCover = true)

        assertEquals(2, downloads.size)
        assertEquals(MediaType.LIVE_PHOTO, downloads[0].type)
        assertEquals(MediaType.IMAGE, downloads[1].type)
        assertEquals(livePhoto.coverUrl, downloads[1].url)
    }

    @Test fun neverDuplicatesCoverForOrdinaryMedia() {
        val image = livePhoto.copy(type = MediaType.IMAGE)

        assertEquals(1, DownloadMediaPlanner.expand(image, includeLiveCover = true).size)
    }
}
