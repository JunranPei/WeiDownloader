package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeiboMediaInventoryTest {
    private val a = "008no9MSly1i00me6rqirj32np3juqv9"
    private val b = "008no9MSly1i00me7od3pj33gr4mokjp"
    private val c = "008no9MSly1i00me7h741j32rt3pce85"
    private val normalizer = WeiboWorkNormalizer(false, object : WeiboMediaMetadataProbe {
        override fun imageDimensions(url: String): Pair<Int, Int>? = null
        override fun mediaMetadata(url: String): WeiboMediaProbeResult? = null
    })
    private fun photo(id: String, host: Int = 1) = """{"pid":"$id","large":{"url":"https://wx$host.sinaimg.cn/large/$id.jpg"}}"""
    private fun status(vararg ids: String) = JSONObject("""{"id":"123","pics":[${ids.joinToString(",") { photo(it) }}]}""")
    private fun parse(data: JSONObject) = normalizer.parseMedia(data, QualityPreference.HIGHEST)
    private fun ids(items: List<MediaItem>) = items.map { it.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.') }
    private fun resolve(vararg data: JSONObject) = WeiboStatusWorkResolver.resolve(
        data.map { payload -> { payload } },
        { normalizer.normalizeStatus(it, "123", QualityPreference.HIGHEST, "author") },
    )!!

    @Test
    fun complementaryEqualLengthProvidersRetainTheirUnionInSourceOrder() {
        assertEquals(listOf(a, b, c), ids(resolve(status(a, c), status(a, b, c).apply {
            getJSONArray("pics").remove(0)
        }).media))
    }

    @Test
    fun missingPictureInMiddleIsInsertedBeforeItsNextSourceNeighbour() {
        val data = status(a, c).put("pic_ids", org.json.JSONArray(listOf(a, b, c)))
        assertEquals(listOf(a, b, c), ids(parse(data)))
    }

    @Test
    fun mixedOrderOverridesPhotoOnlyProjectionAndDuplicateCdnHosts() {
        val data = JSONObject("""{"id":"123","pic_ids":["$a","$b"],
          "pic_infos":{"$a":${photo(a, 1)},"$b":${photo(b, 1)}},
          "mix_media_info":{"items":[
            {"type":"pic","id":"$a","data":${photo(a, 4)}},
            {"type":"video","id":"1034:video","data":{"object_id":"1034:video","media_info":{"stream_url":"https://f.video.weibocdn.com/video.mp4"}}},
            {"type":"pic","id":"$b","data":${photo(b, 3)}}]}}
        """)
        val media = parse(data)
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), media.map { it.type })
        assertEquals(listOf(1, 2, 3), media.map { it.ordinal })
    }

    @Test
    fun laterLivePhotoReplacesStaticPreviewWithoutDuplicatingCover() {
        val live = status(a).apply {
            getJSONArray("pics").getJSONObject(0).put("type", "livephoto").put("fid", "live-clip.mov")
        }
        val media = resolve(status(a), live).media
        assertEquals(1, media.size)
        assertEquals(MediaType.LIVE_PHOTO, media.single().type)
    }

    @Test
    fun componentFallbackFillsVideoEvenWhenPicturesAlreadyParsed() {
        val data = JSONObject("""{"id":"123","mix_media_info":{"items":[
            {"type":"pic","id":"$a","data":${photo(a)}},
            {"type":"video","id":"1034:video","data":{"object_id":"1034:video"}},
            {"type":"pic","id":"$b","data":${photo(b)}}]}}""")
        val fallback = MediaItem(MediaType.VIDEO, "https://f.video.weibocdn.com/video.mp4", MediaQuality.HD_BEST,
            "123", 1, videoObjectId = "1034:video")
        val work = normalizer.normalizeStatus(data, "123", QualityPreference.HIGHEST, "author", listOf(fallback))!!
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), work.media.map { it.type })
    }

    @Test
    fun forwardedPictureIdOnlyInventoryIsRecognizedAndKeepsRootPostId() {
        val data = JSONObject("""{"id":"root","retweeted_status":{"id":"original","pic_ids":["$a","$b"]}}""")
        val media = parse(data)
        assertEquals(listOf(a, b), ids(media))
        assertTrue(media.all { it.postId == "root" })
    }

    @Test
    fun differentPostResponsesAreNeverCombined() {
        try {
            resolve(status(a), status(b).put("id", "different"))
            fail("Mismatched status must not contaminate the first post")
        } catch (_: IllegalStateException) { }
    }

    private fun missingVideo() = JSONObject("""{"id":"123","mix_media_info":{"items":[
        {"type":"pic","id":"$a","data":${photo(a)}},
        {"type":"video","id":"1034:video","data":{"object_id":"1034:video"}},
        {"type":"pic","id":"$b","data":${photo(b)}}]}}""")

    @Test(expected = WeiboIncompleteMediaException::class)
    fun unresolvedVideoCannotSilentlyBecomeSuccessfulPhotoOnlyPost() {
        resolve(missingVideo())
    }

    @Test(expected = WeiboIncompleteMediaException::class)
    fun declaredTotalLargerThanAllKnownSlotsIsNotReportedAsComplete() {
        resolve(status(a, b).put("pic_num", 10))
    }

    @Test
    fun completionRunsAfterProviderUnionAndOnlyLoadsMissingVideos() {
        val partial = missingVideo()
        val calls = mutableListOf<String>()
        val result = WeiboStatusWorkResolver.resolve(
            listOf({ partial }, { status(a, b) }),
            { normalizer.normalizeStatus(it, "123", QualityPreference.HIGHEST, "author") },
            { work -> WeiboStatusWorkResolver.completeVideos(work) { ref ->
                calls += ref.objectId
                MediaItem(MediaType.VIDEO, "https://example.org/video.mp4", MediaQuality.HD_BEST,
                    "component", 1, videoObjectId = ref.objectId)
            } },
        )!!
        assertEquals(listOf("1034:video"), calls)
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), result.media.map { it.type })
        assertEquals(listOf(1, 2, 3), result.media.map { it.ordinal })
        assertTrue(result.media.all { it.postId == "123" })
        WeiboStatusWorkResolver.completeVideos(result) { error("Complete video must not be requested again") }
    }

    @Test(expected = WeiboIncompleteMediaException::class)
    fun failedOrWrongObjectComponentDoesNotHideIncompleteInventory() {
        WeiboStatusWorkResolver.resolve(listOf({ missingVideo() }),
            { normalizer.normalizeStatus(it, "123", QualityPreference.HIGHEST, "author") },
            { work -> WeiboStatusWorkResolver.completeVideos(work) {
                MediaItem(MediaType.VIDEO, "https://example.org/wrong.mp4", MediaQuality.HD_BEST,
                    "123", 2, videoObjectId = "1034:wrong")
            } })
    }

    @Test
    fun topLevelLiveHintsUpgradeExistingPicsByPictureId() {
        val data = status(b, a).put("pic_ids", org.json.JSONArray(listOf(a, b)))
            .put("pic_video", "0:live-clip.mov")
        val items = parse(data)
        assertEquals(MediaType.LIVE_PHOTO, items.single { it.sourceId == "picture:$a" }.type)
        assertEquals(MediaType.IMAGE, items.single { it.sourceId == "picture:$b" }.type)
    }

    @Test
    fun topLevelLiveHintsAlsoApplyToMixedPictureContainer() {
        val data = JSONObject("""{"id":"123","pic_ids":["$a"],"pic_video":"0:clip.mov",
            "mix_media_info":{"items":[{"type":"pic","id":"$a","data":${photo(a)}}]}}""")
        assertEquals(MediaType.LIVE_PHOTO, resolve(data).media.single().type)
    }

    @Test(expected = WeiboIncompleteMediaException::class)
    fun livePhotoWithoutClipCannotBeReportedAsStaticImageSuccess() {
        resolve(status(a).apply { getJSONArray("pics").getJSONObject(0).put("type", "livephoto") })
    }

    @Test
    fun matchingOrdinalWithoutIdentityCannotReplaceVideo() {
        val first = MediaItem(MediaType.VIDEO, "https://example.org/a.mp4", MediaQuality.HD_BEST, "123", 1)
        val other = first.copy(url = "https://example.org/b.mp4")
        val work = WeiboWorkData("123", "", "", listOf(first))
        assertEquals(work, WeiboVideoWorkResolver.mergePost(work, work.copy(media = listOf(other))))
    }

    @Test
    fun videoInParentSurvivesTextOnlyForwardedPost() {
        val data = JSONObject("""{"id":"123","retweeted_status":{"id":"other","text":"plain text"},
            "page_info":{"type":"video","object_id":"1034:video","media_info":{"stream_url":"https://example.org/video.mp4"}}}""")
        assertEquals("1034:video", resolve(data).media.single().videoObjectId)
    }

    @Test
    fun providerOrderAndRepeatedCdnVariantsDoNotChangeMixedInventory() {
        val mobile = status(a, b, c)
        val desktop = status(a, b, c).apply {
            getJSONArray("pics").put(1, JSONObject(photo(b, 4)))
        }
        val expected = listOf(a, b, c)
        assertEquals(expected, ids(resolve(mobile, desktop, mobile).media))
        assertEquals(expected, ids(resolve(desktop, mobile, desktop).media))
    }

    @Test
    fun pageObjectWithoutTypeStillKeepsUnresolvedVideoForCompletion() {
        val data = status(a).put("page_info", JSONObject("""{"media_info":{"object_id":"1034:video"}}"""))
        val work = normalizer.normalizeStatus(data, "123", QualityPreference.HIGHEST, "author")!!
        assertEquals(listOf("1034:video"), work.inventory.missing(work.media).map { it.objectId })
    }

    @Test
    fun originalImageFieldAndAlternateVideoFieldArePlayableWithoutObjectIds() {
        val data = JSONObject("""{"id":"123","pics":[{"original":{"url":"https://example.org/image.jpg"}}],
            "page_info":{"type":"video","media_info":{"mp4_1080p_mp4":"https://example.org/movie.mp4"}}}""")
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), resolve(data).media.map { it.type })
    }

    @Test
    fun existingDownloadLocatorSurvivesNewIdentityAndCdnHost() {
        val original = parse(status(a)).single().copy(sourceId = "")
        val post = WeiboPost("123", "", "", "https://weibo.com/1/123", listOf(original))
        val locator = DownloadLocator.create(Platform.WEIBO, post, original, false)
        val fresh = parse(JSONObject("""{"id":"123","pics":[${photo(a, 4)}]}""")).single()
        assertEquals(fresh.url, locator.select(post.copy(media = listOf(fresh))).url)
    }
}
