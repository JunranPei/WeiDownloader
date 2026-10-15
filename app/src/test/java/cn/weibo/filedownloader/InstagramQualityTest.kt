package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramQualityTest {
    private val target = InstagramPostTarget("Cdh61wHMarP", "p")

    @Test fun convertsTheReportedShortcodeWithoutIntegerOverflow() {
        assertEquals("2837808020710337231", InstagramRequests.mediaId(target.shortcode))
    }

    @Test fun nativeRequestKeepsAuthenticationAndRemovesBrowserSizing() {
        assertEquals("sessionid=signed%3Atoken; csrftoken=csrf; ds_user_id=123",
            InstagramRequests.mobileCookie("wd=960x600; sessionid=signed%3Atoken; dpr=1; csrftoken=csrf; ds_user_id=123; ig_vw=960"))
        assertTrue(InstagramRequests.hasSession("sessionid=abc"))
        assertFalse(InstagramRequests.hasSession("sessionid=; csrftoken=abc"))
        assertFalse(InstagramRequests.isInstagramUrl("https://www.instagram.com.evil.example/"))
    }

    @Test fun combinesMobileOriginalWithWebPreviewEvenIfWebRequestSucceeds() {
        val calls = mutableListOf<InstagramPostSources.Source>()
        val result = InstagramPostSources.load(target, "sessionid=saved") { source ->
            calls += source
            if (source == InstagramPostSources.Source.MOBILE) payload(image("2048", 2048, 1266))
            else payload(image("960", 960, 594))
        }
        val post = requireNotNull(InstagramPostNormalizer.normalize(result.payload, target.canonicalUrl))
        assertEquals(listOf(InstagramPostSources.Source.MOBILE, InstagramPostSources.Source.WEB), calls)
        assertTrue(result.mobileSucceeded)
        assertEquals("https://s.cdninstagram.com/2048.jpg", post.media.single().url)
        assertEquals(2, post.media.single().variants.size)
    }

    @Test fun expiredSessionRetainsPublicWebMedia() {
        val result = InstagramPostSources.load(target, "sessionid=expired") { source ->
            if (source == InstagramPostSources.Source.MOBILE) throw InstagramException("HTTP 401")
            payload(image("960", 960, 594))
        }
        assertTrue(result.mobileAttempted)
        assertFalse(result.mobileSucceeded)
        assertEquals(960, InstagramPostNormalizer.normalize(result.payload, target.canonicalUrl)?.media?.single()?.variants?.first()?.width)
    }

    @Test fun anonymousModeSkipsNativeApiAndRetainsShortcodeFallback() {
        val calls = mutableListOf<InstagramPostSources.Source>()
        val result = InstagramPostSources.load(target, "csrftoken=anonymous") { source ->
            calls += source
            if (source == InstagramPostSources.Source.WEB) throw InstagramException("HTTP 403")
            payload(image("960", 960, 594))
        }
        assertFalse(result.mobileAttempted)
        assertEquals(listOf(InstagramPostSources.Source.WEB, InstagramPostSources.Source.SHORTCODE), calls)
    }

    @Test fun mergesCarouselByMediaIdWhenResponsesHaveDifferentOrdering() {
        fun carousel(vararg images: JSONObject) = JSONObject().put("items", JSONArray().put(JSONObject()
            .put("code", target.shortcode).put("carousel_media", JSONArray(images.toList()))))
        val first = image("first-low", 960, 594).put("pk", "111")
        val second = image("second-low", 960, 594).put("pk", "222")
        val result = InstagramPostSources.merge(listOf(carousel(first, second), carousel(
            image("second-high", 2048, 1266).put("pk", "222"),
            image("first-high", 2048, 1266).put("pk", "111"))), target)!!
        val post = InstagramPostNormalizer.normalize(result, target.canonicalUrl)!!
        assertTrue(post.media[0].url.endsWith("first-high.jpg"))
        assertTrue(post.media[1].url.endsWith("second-high.jpg"))
        assertEquals(listOf(1, 2), post.media.map { it.ordinal })
    }

    @Test fun ignoresPayloadForAnotherPost() {
        val unrelated = payload(image("wrong", 4096, 2532))
        InstagramPostSources.item(unrelated)!!.put("code", "WrongPost12")
        val merged = InstagramPostSources.merge(listOf(payload(image("right", 960, 594)), unrelated), target)!!
        assertTrue(InstagramPostNormalizer.normalize(merged, target.canonicalUrl)!!.media.single().url.endsWith("right.jpg"))
    }

    @Test fun sizeLimitRemovalPreservesSignatureEscapingAndOtherParameters() {
        assertEquals("https://s.cdninstagram.com/a.jpg?sig=a%2Bb%3D&oe=abc",
            InstagramRequests.withoutSizeLimit("https://s.cdninstagram.com/a.jpg?sig=a%2Bb%3D&se=7&oe=abc"))
        assertEquals("https://example.com/a.jpg?se=7", InstagramRequests.withoutSizeLimit("https://example.com/a.jpg?se=7"))
    }

    @Test fun onlySelectsSizeLimitFreeUrlAfterReadingLargerActualDimensions() {
        val raw = image("preview", 960, 594)
        raw.getJSONObject("image_versions2").getJSONArray("candidates").getJSONObject(0)
            .put("url", "https://s.cdninstagram.com/a.jpg?se=7&sig=kept")
        val originalPayload = payload(raw)
        val enhanced = InstagramImageQuality.enhance(originalPayload) { url ->
            if ("se=" in url) InstagramImageQuality.Dimensions(960, 594) else InstagramImageQuality.Dimensions(2048, 1266)
        }
        val media = InstagramPostNormalizer.normalize(enhanced, target.canonicalUrl)!!.media.single()
        assertEquals("https://s.cdninstagram.com/a.jpg?sig=kept", media.url)
        assertEquals(MediaQuality.ORIGINAL_BEST, media.quality)
        assertEquals(2048, media.variants.first().width)
        assertEquals(1, raw.getJSONObject("image_versions2").getJSONArray("candidates").length())
        assertEquals(MediaQuality.PLATFORM_BEST, media.selectVariant("960×594").quality)
        assertEquals(MediaQuality.ORIGINAL_BEST, media.selectVariant("960×594").selectVariant("2048×1266").quality)
    }

    @Test fun rejectedOrStillReducedUnlimitedUrlDoesNotReplaceWorkingPreview() {
        for (unlimitedSize in listOf(null, InstagramImageQuality.Dimensions(640, 396))) {
            val raw = image("preview", 960, 594)
            val url = "https://s.cdninstagram.com/a.jpg?se=7&sig=kept"
            raw.getJSONObject("image_versions2").getJSONArray("candidates").getJSONObject(0).put("url", url)
            val enhanced = InstagramImageQuality.enhance(payload(raw)) {
                if ("se=" in it) InstagramImageQuality.Dimensions(960, 594) else unlimitedSize
            }
            val media = InstagramPostNormalizer.normalize(enhanced, target.canonicalUrl)!!.media.single()
            assertEquals(url, media.url)
            assertEquals(MediaQuality.PLATFORM_BEST, media.quality)
            assertEquals(2048, media.originalWidth)
            assertEquals(1266, media.originalHeight)
        }
    }

    @Test fun metadataAloneDoesNotProveOriginalDimensions() {
        val media = InstagramPostNormalizer.normalize(payload(image("original", 2048, 1266)), target.canonicalUrl)!!.media.single()
        assertEquals(MediaQuality.PLATFORM_BEST, media.quality)
    }

    @Test fun rechecksRankingWhenActualFileIsSmallerThanItsMetadata() {
        val raw = image("alleged-original", 2048, 1266)
        raw.getJSONObject("image_versions2").getJSONArray("candidates").put(JSONObject()
            .put("url", "https://s.cdninstagram.com/true-original.jpg").put("width", 1440).put("height", 890))
        val enhanced = InstagramImageQuality.enhance(payload(raw)) { url ->
            if ("alleged" in url) InstagramImageQuality.Dimensions(960, 594)
            else InstagramImageQuality.Dimensions(2048, 1266)
        }
        val media = InstagramPostNormalizer.normalize(enhanced, target.canonicalUrl)!!.media.single()
        assertTrue(media.url.endsWith("true-original.jpg"))
        assertEquals(MediaQuality.ORIGINAL_BEST, media.quality)
    }

    private fun payload(image: JSONObject) = JSONObject().put("items", JSONArray().put(
        JSONObject(image.toString()).put("code", target.shortcode).put("pk", InstagramRequests.mediaId(target.shortcode))))

    private fun image(name: String, width: Int, height: Int) = JSONObject()
        .put("media_type", 1).put("original_width", 2048).put("original_height", 1266)
        .put("image_versions2", JSONObject().put("candidates", JSONArray().put(JSONObject()
            .put("url", "https://s.cdninstagram.com/$name.jpg").put("width", width).put("height", height))))
}
