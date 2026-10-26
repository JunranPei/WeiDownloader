package cn.weibo.filedownloader

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WeiboStatusWorkResolverTest {
    private val normalizer = WeiboWorkNormalizer(false, object : WeiboMediaMetadataProbe {
        override fun imageDimensions(url: String): Pair<Int, Int>? = null
        override fun mediaMetadata(url: String): WeiboMediaProbeResult? = null
    })

    private fun gallery(id: String): JSONObject = javaClass.getResource("/weibo/$id.json")!!
        .readText().let(::JSONObject)

    private fun preview(full: JSONObject): JSONObject = JSONObject(full.toString()).apply {
        val items = getJSONObject("mix_media_info").getJSONArray("items")
        put("mix_media_info", JSONObject().put("items", JSONArray((0 until 9).map { items.get(it) })))
        put("pic_ids", JSONArray((0 until 9).mapNotNull { index ->
            items.getJSONObject(index).takeIf { it.optString("type") == "pic" }?.getString("id")
        }))
    }

    private fun resolveGallery(vararg statuses: JSONObject): WeiboWorkData = WeiboStatusWorkResolver.resolve(
        statuses.map { status -> { status } },
        { normalizer.normalizeStatus(it, it.optString("id"), QualityPreference.HIGHEST, "author") },
    )!!

    @Test
    fun bothReportedMixedGalleriesKeepAllEighteenItemsInSourceOrder() {
        for (id in listOf("5150598341593110", "5150605895794752")) {
            val full = gallery(id)
            val expected = normalizer.parseMedia(full, QualityPreference.HIGHEST)
            assertEquals(18, expected.size)
            assertEquals(16, expected.count { it.type == MediaType.IMAGE })
            assertEquals(2, expected.count { it.type == MediaType.VIDEO })
            val resolved = resolveGallery(preview(full), full)
            assertEquals(id, 18, resolved.media.size)
            assertEquals(expected.map { it.url }, resolved.media.map { it.url })
            assertEquals(expected.map { it.type }, resolved.media.map { it.type })
            assertEquals((1..18).toList(), resolved.media.map { it.ordinal })
            val items = full.getJSONObject("mix_media_info").getJSONArray("items")
            val expectedIds = (0 until items.length()).map { items.getJSONObject(it).getString("id") }
            assertEquals(expectedIds, resolved.media.map {
                if (it.type == MediaType.VIDEO) it.videoObjectId else it.url.substringAfterLast('/').substringBeforeLast('.')
            })
        }
    }

    @Test
    fun laterPreviewCannotShrinkFullGalleryAndRepeatedProvidersDoNotDuplicateIt() {
        val full = gallery("5150598341593110")
        val expected = normalizer.parseMedia(full, QualityPreference.HIGHEST)
        assertEquals(expected, resolveGallery(full, preview(full), full).media)
    }

    @Test
    fun largerInventoryKeepsFirstProvidersTitleAndAuthor() {
        val full = gallery("5150598341593110")
        val first = preview(full).put("text_raw", "first title")
            .put("user", JSONObject().put("screen_name", "first author"))
        val resolved = resolveGallery(first, full)
        assertEquals(18, resolved.media.size)
        assertEquals("first title", resolved.title)
        assertEquals("first author", resolved.author)
    }

    @Test
    fun expandingInventoryStillKeepsEarlierHigherQualityVideo() {
        val higher = work(1080)
        val lower = work(720)
        val full = lower.copy(media = lower.media + MediaItem(
            MediaType.IMAGE, "https://wx1.sinaimg.cn/large/extra.jpg", MediaQuality.ORIGINAL_BEST, "123", 2,
        ))
        val resolved = WeiboStatusWorkResolver.resolve(
            listOf({ JSONObject().put("full", false) }, { JSONObject().put("full", true) }),
            { if (it.getBoolean("full")) full else higher },
        )!!
        assertEquals(2, resolved.media.size)
        assertEquals(higher.media.single().url, resolved.media.first().url)
        assertEquals(setOf("720p", "1080p"), resolved.media.first().variants.map { it.key }.toSet())
        assertEquals(full.media.last(), resolved.media.last())
    }

    @Test
    fun singleSourceInventoriesKeepTheirActualSizeAroundNineItemBoundary() {
        for (size in listOf(1, 8, 9, 10, 18)) {
            val full = gallery("5150605895794752")
            val items = full.getJSONObject("mix_media_info").getJSONArray("items")
            full.put("mix_media_info", JSONObject().put("items", JSONArray((0 until size).map { items.get(it) })))
            full.remove("pic_ids")
            full.put("pic_num", size)
            assertEquals(size, resolveGallery(full).media.size)
        }
    }

    @Test
    fun capturedMobileGalleriesRecoverAllPicturesWhenDesktopProviderFails() {
        for (id in listOf("5150598341593110", "5150605895794752")) {
            val mobile = gallery("$id-mobile")
            val resolved = WeiboStatusWorkResolver.resolve(
                listOf({ mobile }, { error("desktop unavailable") }),
                { normalizer.normalizeStatus(it, id, QualityPreference.HIGHEST, "author") },
            )!!
            assertEquals(id, 18, resolved.media.size)
            assertEquals(16, resolved.media.count { it.type == MediaType.IMAGE })
            assertEquals(2, resolved.media.count { it.type == MediaType.VIDEO })
            assertEquals((1..18).toList(), resolved.media.map { it.ordinal })
            val expected = gallery(id).getJSONObject("mix_media_info").getJSONArray("items")
            for (index in 0 until expected.length()) {
                val item = expected.getJSONObject(index)
                val media = resolved.media[index]
                if (item.getString("type") == "pic") {
                    assertEquals(item.getString("id"), media.url.substringAfterLast('/').substringBeforeLast('.'))
                } else assertEquals(MediaType.VIDEO, media.type)
            }
            // The two real mobile video URLs survive picture completion unchanged.
            val preview = mobile.getJSONArray("pics")
            val videoUrls = (0 until preview.length()).map { preview.getJSONObject(it) }
                .filter { it.optString("type") == "video" }.map { it.getString("videoSrc") }
            assertEquals(videoUrls, resolved.media.filter { it.type == MediaType.VIDEO }.map { it.url })
        }
    }

    @Test
    fun capturedMobileAndDesktopDescribeOneInventoryInEitherProviderOrder() {
        for (id in listOf("5150598341593110", "5150605895794752")) {
            val mobile = gallery("$id-mobile")
            val desktop = gallery(id)
            val expected = normalizer.parseMedia(desktop, QualityPreference.HIGHEST)
            for (resolved in listOf(resolveGallery(mobile, desktop), resolveGallery(desktop, mobile))) {
                assertEquals("$id ${resolved.media.map { Triple(it.type, it.sourceId, it.videoObjectId) }}", 18, resolved.media.size)
                assertEquals(expected.map { it.type }, resolved.media.map { it.type })
                assertEquals(expected.map { if (it.type == MediaType.VIDEO) it.videoObjectId else it.sourceId },
                    resolved.media.map { if (it.type == MediaType.VIDEO) it.videoObjectId else it.sourceId })
            }
        }
    }

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
