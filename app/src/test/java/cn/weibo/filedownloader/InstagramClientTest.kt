package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramClientTest {
    @Test
    fun parsesPostAndReelLinks() {
        assertEquals("ABC_def-12", InstagramLinkParser.targetFromUrl("https://www.instagram.com/p/ABC_def-12/?img_index=2")?.shortcode)
        assertEquals("C0ffee123", InstagramLinkParser.targetFromUrl("https://instagr.am/reel/C0ffee123/")?.shortcode)
        assertTrue(InstagramLinkParser.isSupported("https://instagram.com/reels/C0ffee123/"))
        assertFalse(InstagramLinkParser.isSupported("https://instagram.com/example/"))
    }

    @Test
    fun normalizesCarouselImagesAndVideo() {
        val image = JSONObject()
            .put("media_type", 1)
            .put("image_versions2", JSONObject().put("candidates", JSONArray()
                .put(candidate("https://cdn.example/small.jpg", 640, 640))
                .put(candidate("https://cdn.example/full.jpg", 1440, 1440))))
        val video = JSONObject()
            .put("media_type", 2)
            .put("image_versions2", JSONObject().put("candidates", JSONArray().put(candidate("https://cdn.example/cover.jpg", 720, 1280))))
            .put("video_versions", JSONArray()
                .put(candidate("https://cdn.example/low.mp4", 360, 640))
                .put(candidate("https://cdn.example/high.mp4", 1080, 1920)))
        val item = JSONObject()
            .put("code", "Carousel1")
            .put("caption", JSONObject().put("text", "A carousel"))
            .put("user", JSONObject().put("username", "creator").put("full_name", "Creator Name"))
            .put("carousel_media", JSONArray().put(image).put(video))
        val payload = JSONObject().put("items", JSONArray().put(item))

        val post = InstagramPostNormalizer.normalize(payload, "https://instagram.com/p/Carousel1/")

        assertNotNull(post)
        assertEquals("Creator Name", post?.author)
        assertEquals(Platform.INSTAGRAM, post?.creator?.platform)
        assertEquals(2, post?.media?.size)
        assertEquals("https://cdn.example/full.jpg", post?.media?.get(0)?.url)
        assertEquals(MediaType.VIDEO, post?.media?.get(1)?.type)
        assertEquals("https://cdn.example/high.mp4", post?.media?.get(1)?.url)
        assertEquals("https://cdn.example/cover.jpg", post?.media?.get(1)?.thumbnailUrl)
    }

    private fun candidate(url: String, width: Int, height: Int) = JSONObject()
        .put("url", url)
        .put("width", width)
        .put("height", height)
}
