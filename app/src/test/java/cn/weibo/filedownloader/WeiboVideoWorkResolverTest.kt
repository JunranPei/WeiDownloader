package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeiboVideoWorkResolverTest {
    private val oid = "1034:5352519325384790"
    private val mid = "5352524651433064"
    private val normalizer = WeiboWorkNormalizer(false, object : WeiboMediaMetadataProbe {
        override fun imageDimensions(url: String): Pair<Int, Int>? = null
        override fun mediaMetadata(url: String): WeiboMediaProbeResult? = null
    })

    private fun component() = JSONObject("""{
        "mid":"$mid", "nickname":"author",
        "urls":{"高清 1080P":"//f.video.weibocdn.com/high.mp4",
                "高清 720P":"//f.video.weibocdn.com/low.mp4"}
    }""")

    private fun normalize(detail: JSONObject) = normalizer.normalizeVideoComponent(
        detail, oid, QualityPreference.HIGHEST, "unknown",
    )

    @Test
    fun followsActualMidRatherThanVideoFidAndPrefersMatchingStatus() {
        val work = WeiboVideoWorkResolver.resolve(
            providers = listOf({ component() }),
            resolveStatus = { id ->
                assertEquals(mid, id)
                WeiboWorkData(id, "original status", "author", normalize(component())!!.media)
            },
            normalizeComponent = ::normalize,
        )
        assertEquals(mid, work?.id)
        assertEquals("original status", work?.title)
    }

    @Test
    fun midWithoutComponentUrlsCanStillResolveStatus() {
        val expected = normalize(component())!!
        val work = WeiboVideoWorkResolver.resolve(
            listOf({ JSONObject().put("mid", mid) }), { expected }, ::normalize,
        )
        assertSame(expected, work)
    }

    @Test
    fun status720pDoesNotReplaceComponent1080pAndKeepsStatusIdentity() {
        val statusVideo = MediaItem(
            MediaType.VIDEO, "https://f.video.weibocdn.com/status-720.mp4",
            MediaQuality.PLAYLIST_MAX, mid, 3,
            variants = listOf(MediaVariant("720p", "https://f.video.weibocdn.com/status-720.mp4", 1280, 720)),
            selectedVariantKey = "720p", videoObjectId = oid,
        )
        val work = WeiboVideoWorkResolver.resolve(listOf({ component() }), {
            WeiboWorkData(mid, "original title", "author", listOf(statusVideo))
        }, ::normalize)!!
        assertEquals("https://f.video.weibocdn.com/high.mp4", work.media.single().url)
        assertEquals(3, work.media.single().ordinal)
        assertEquals(mid, work.media.single().postId)
        assertEquals("original title", work.title)
    }

    @Test
    fun statusHigherResolutionIsKeptWhenComponentIsLower() {
        val statusVideo = MediaItem(
            MediaType.VIDEO, "https://f.video.weibocdn.com/status-4k.mp4",
            MediaQuality.PLAYLIST_MAX, mid, 1,
            variants = listOf(MediaVariant("4k", "https://f.video.weibocdn.com/status-4k.mp4", 3840, 2160)),
            selectedVariantKey = "4k", videoObjectId = oid,
        )
        val work = WeiboVideoWorkResolver.resolve(listOf({ component() }), {
            WeiboWorkData(mid, "original title", "author", listOf(statusVideo))
        }, ::normalize)!!
        assertEquals(statusVideo.url, work.media.single().url)
        assertEquals("4k", work.media.single().selectedVariantKey)
        assertTrue(work.media.single().variants.any { it.url.endsWith("high.mp4") })
    }

    @Test
    fun normalStatusRouteUsesComponent1080pWhenStatusOnlyHas720p() {
        val statusVideo = MediaItem(
            MediaType.VIDEO, "https://f.video.weibocdn.com/status-720.mp4",
            MediaQuality.PLAYLIST_MAX, mid, 1,
            variants = listOf(MediaVariant("720p", "https://f.video.weibocdn.com/status-720.mp4", 1280, 720)),
            selectedVariantKey = "720p", videoObjectId = oid,
        )
        val status = WeiboWorkData(mid, "original status", "author", listOf(statusVideo))
        val component = normalize(component())!!
        val merged = WeiboVideoWorkResolver.upgradeStatus(status) { component }
        assertEquals("https://f.video.weibocdn.com/high.mp4", merged.media.single().url)
        assertEquals(mid, merged.media.single().postId)
        assertEquals("original status", merged.title)
    }

    @Test
    fun componentFailureNeverRemovesWorkingStatusMedia() {
        val status = normalize(component())!!
        assertSame(status, WeiboVideoWorkResolver.upgradeStatus(status) { throw IllegalStateException("HTTP failed") })
        assertSame(status, WeiboVideoWorkResolver.upgradeStatus(status) { null })
    }

    @Test
    fun componentEnhancementPreservesMixedPostAndImages() {
        val video = normalize(component())!!
        val image = MediaItem(MediaType.IMAGE, "https://wx1.sinaimg.cn/large/test.jpg", MediaQuality.ORIGINAL_BEST, mid, 2)
        val mixed = video.copy(media = video.media + image)
        assertSame(mixed, WeiboVideoWorkResolver.upgradeStatus(mixed) { video })
        val imagePost = video.copy(media = listOf(image))
        assertSame(imagePost, WeiboVideoWorkResolver.upgradeStatus(imagePost) { video })
    }

    @Test
    fun unavailableStatusFallsBackToProtocolRelativeComponentUrls() {
        val work = WeiboVideoWorkResolver.resolve(
            listOf({ component() }), { throw IllegalStateException("status unavailable") }, ::normalize,
        )
        assertEquals(mid, work?.id)
        assertEquals(mid, work?.media?.single()?.postId)
        assertEquals("https://f.video.weibocdn.com/high.mp4", work?.media?.single()?.url)
        assertEquals(2, work?.media?.single()?.variants?.size)
    }

    @Test
    fun emptyH5ResponseContinuesToDesktopProvider() {
        var desktopCalled = false
        val work = WeiboVideoWorkResolver.resolve(
            listOf({ JSONObject().put("urls", JSONObject()) }, {
                desktopCalled = true
                component()
            }), { null }, ::normalize,
        )
        assertTrue(desktopCalled)
        assertNotNull(work)
    }

    @Test
    fun providerOrNormalizationFailureContinuesToNextProvider() {
        val work = WeiboVideoWorkResolver.resolve(
            listOf({ throw IllegalStateException("H5 error") }, { JSONObject().put("broken", true) }, { component() }),
            { null }, { detail ->
                if (detail.optBoolean("broken")) throw IllegalStateException("Invalid component")
                normalize(detail)
            },
        )
        assertNotNull(work)
    }

    @Test
    fun missingOrInvalidMidNeverGuessesStatusIdFromFid() {
        listOf("", "null", oid, "123", "https://weibo.com/example").forEach { invalid ->
            val detail = component().put("mid", invalid)
            assertNull(WeiboVideoWorkResolver.statusId(detail))
            val work = WeiboVideoWorkResolver.resolve(listOf({ detail }), {
                error("Invalid mid must not be used as a status id")
            }, ::normalize)
            assertEquals("5352519325384790", work?.id)
        }
    }

    @Test
    fun videoStatusSelectsOnlyMatchingVideoAndKeepsOriginalOrdinal() {
        val status = JSONObject("""{
          "id":"$mid", "text_raw":"original title", "user":{"screen_name":"author"},
          "mix_media_info":{"items":[
            {"type":"pic", "data":{"large":{"url":"https://wx1.sinaimg.cn/large/pic.jpg"}}},
            {"type":"video", "data":{"object_id":"1034:other", "media_info":{"stream_url":"https://f.video.weibocdn.com/other.mp4"}}},
            {"type":"video", "data":{"object_id":"$oid", "media_info":{"playback_list":[
              {"play_info":{"url":"https://f.video.weibocdn.com/target.mp4", "width":1920,"height":1080}, "meta":{"quality_desc":"1080p"}}
            ]}}}
          ]}
        }""")
        val video = normalizer.normalizeVideoStatus(status, mid, oid, QualityPreference.HIGHEST, "unknown")!!
        assertEquals(mid, video.id)
        assertEquals("original title", video.title)
        assertEquals("author", video.author)
        assertEquals("https://f.video.weibocdn.com/target.mp4", video.media.single().url)
        assertEquals(3, video.media.single().ordinal)
        assertEquals(3, normalizer.parseMedia(status, QualityPreference.HIGHEST).size)
    }

    @Test
    fun mismatchedStatusCannotSelectAnotherVideo() {
        val status = JSONObject("""{"id":"$mid", "page_info":{
          "object_id":"1034:another", "media_info":{"stream_url":"https://f.video.weibocdn.com/other.mp4"}
        }}""")
        assertNull(normalizer.normalizeVideoStatus(status, mid, oid, QualityPreference.HIGHEST, "unknown"))
    }

    @Test
    fun pageVideoAndStatusHaveSameFileIdentityAndQuality() {
        val status = JSONObject("""{"id":"$mid", "page_info":{
          "object_id":"$oid", "media_info":{"playback_list":[
            {"play_info":{"url":"https://f.video.weibocdn.com/target.mp4", "width":1920,"height":1080}, "meta":{"quality_desc":"1080p"}}
          ]}
        }}""")
        val fromVideo = normalizer.normalizeVideoStatus(status, mid, oid, QualityPreference.HIGHEST, "unknown")!!.media.single()
        val fromStatus = normalizer.normalizeStatus(status, mid, QualityPreference.HIGHEST, "unknown")!!.media.single()
        assertEquals(fromStatus, fromVideo)
    }

    @Test
    fun playableH5DoesNotHideHigherDesktopQualityAndBothTiersRemainSelectable() {
        val lower = component().put("urls", JSONObject().put("720p", "https://f.video.weibocdn.com/h5.mp4"))
        var desktopCalled = false
        val work = WeiboVideoWorkResolver.resolve(listOf({ lower }, { desktopCalled = true; component() }), { null }, ::normalize)!!
        assertTrue(desktopCalled)
        assertEquals("https://f.video.weibocdn.com/high.mp4", work.media.single().url)
        assertTrue(work.media.single().variants.any { it.url.endsWith("h5.mp4") })
    }

    @Test
    fun laterProviderFailureKeepsEarlierWorkingVideo() {
        val expected = normalize(component())!!
        val actual = WeiboVideoWorkResolver.resolve(listOf({ component() }, { throw IllegalStateException("desktop failed") }), { null }, ::normalize)
        assertEquals(expected, actual)
    }

    @Test
    fun laterStatusMetadataReplacesComponentOnlyFallbackIdentity() {
        val first = component().put("mid", "")
        val actual = WeiboVideoWorkResolver.resolve(listOf({ first }, { component() }), {
            normalize(component())!!.copy(title = "original status", author = "original author")
        }, ::normalize)!!
        assertEquals(mid, actual.id)
        assertEquals(mid, actual.media.single().postId)
        assertEquals("original status", actual.title)
        assertEquals("original author", actual.author)
    }

    @Test
    fun mixedPostUpgradesEachMatchingVideoWithoutChangingImagesOrOrdinals() {
        fun video(objectId: String, ordinal: Int, height: Int) = MediaItem(
            MediaType.VIDEO, "https://example.org/$objectId-$height.mp4", MediaQuality.PLAYLIST_MAX, mid, ordinal,
            variants = listOf(MediaVariant("${height}p", "https://example.org/$objectId-$height.mp4", height * 16 / 9, height)),
            selectedVariantKey = "${height}p", videoObjectId = objectId,
        )
        val image = MediaItem(MediaType.IMAGE, "https://example.org/image.jpg", MediaQuality.ORIGINAL_BEST, mid, 1)
        val first = video(oid, 2, 720)
        val second = video("1034:other", 3, 480)
        val status = WeiboWorkData(mid, "title", "author", listOf(image, first, second))
        val upgraded = WeiboVideoWorkResolver.upgradeVideos(status) { objectId ->
            WeiboWorkData(mid, "component", "different author", listOf(video(objectId, 1, 1080)))
        }
        assertEquals(image, upgraded.media[0])
        assertEquals(listOf(1, 2, 3), upgraded.media.map { it.ordinal })
        assertEquals(listOf(oid, "1034:other"), upgraded.media.drop(1).map { it.videoObjectId })
        assertTrue(upgraded.media.drop(1).all { it.url.endsWith("1080.mp4") })
        assertEquals(status.title, upgraded.title)
        assertEquals(status.author, upgraded.author)
    }

    @Test
    fun mixedPostFailureAndMismatchedObjectNeverReplaceOtherVideos() {
        val video = normalize(component())!!.media.single()
        val image = MediaItem(MediaType.IMAGE, "https://example.org/image.jpg", MediaQuality.ORIGINAL_BEST, mid, 2)
        val status = WeiboWorkData(mid, "title", "author", listOf(video, image))
        assertSame(status, WeiboVideoWorkResolver.upgradeVideos(status) { throw IllegalStateException("offline") })
        val unrelated = status.copy(media = listOf(video.copy(videoObjectId = "1034:wrong")))
        assertSame(status, WeiboVideoWorkResolver.mergePost(status, unrelated))
    }

    @Test
    fun normalizerKeepsObjectIdsForMixedVideos() {
        val status = JSONObject("""{"id":"$mid","mix_media_info":{"items":[
          {"type":"video","data":{"object_id":"$oid","media_info":{"stream_url":"https://example.org/a.mp4"}}},
          {"type":"video","data":{"fid":"1034:other","media_info":{"stream_url":"https://example.org/b.mp4"}}}
        ]}}""")
        assertEquals(listOf(oid, "1034:other"), normalizer.parseMedia(status, QualityPreference.HIGHEST).map { it.videoObjectId })
        assertEquals(listOf(oid, "1034:other"), normalizer.videoObjectIds(status))
        val fromFid = normalizer.normalizeVideoStatus(status, mid, "1034:other", QualityPreference.HIGHEST, "unknown")!!
        assertEquals("https://example.org/b.mp4", fromFid.media.single().url)
        assertEquals(2, fromFid.media.single().ordinal)
    }
}
