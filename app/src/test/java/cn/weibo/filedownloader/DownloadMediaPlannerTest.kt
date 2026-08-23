package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadMediaPlannerTest {
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
