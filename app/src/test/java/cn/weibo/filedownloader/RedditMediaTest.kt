package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedditMediaTest {
    @Test fun failedOrPendingGalleryItemDoesNotReturnPartialSuccess() {
        for (status in listOf("failed", "processing", "invalid")) {
            assertNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"one"},{"media_id":"two"}]},"media_metadata":{"one":{"status":"valid","s":{"u":"https://i.redd.it/one.jpg"}},"two":{"status":"$status","s":{"u":"https://i.redd.it/two.jpg"}}}}"""))
        }
    }

    private fun post(json: String) = RedditPostNormalizer.normalizePost(JSONObject(json), "https://redd.it/abc123")

    @Test fun originalImageWinsOverBlurredPreview() {
        val result = requireNotNull(post("""{"id":"abc123","url_overridden_by_dest":"https://i.redd.it/full.png","preview":{"images":[{"source":{"url":"https://preview.redd.it/full.png?blur=40&amp;width=640"}}]}}"""))
        assertEquals("https://i.redd.it/full.png", result.media.single().url)
    }
    @Test fun unblurredRedditPreviewUsesOriginalHostAndDropsResizeParameters() {
        val result = requireNotNull(post("""{"id":"abc123","preview":{"images":[{"source":{"url":"https://preview.redd.it/full.png?width=640&amp;format=webp&amp;s=signature"}}]}}"""))
        assertEquals("https://i.redd.it/full.png", result.media.single().url)
    }
    @Test fun blurOnlyDataIsNotDownloadableOriginal() {
        assertNull(post("""{"id":"abc123","preview":{"images":[{"source":{"url":"https://preview.redd.it/full.png?blur=40"}}]}}"""))
        assertNull(post("""{"id":"abc123","preview":{"images":[{"source":{"url":"https://external-preview.redd.it/obfuscated.png"}}]}}"""))
    }
    @Test fun mediaViewerPreviewDropsTitlePrefixWhenResolvingOriginal() {
        assertEquals("https://i.redd.it/7gl9uqd9wsth1.jpeg", RedditMediaUrls.original(
            "https://preview.redd.it/ethiopian-civil-war-2026-map-v0-7gl9uqd9wsth1.jpeg?width=640&crop=smart&auto=webp"))
    }
    @Test fun linkPagePreviewDoesNotBecomeAnImageDownload() {
        assertNull(post("""{"id":"abc123","post_hint":"link","url":"https://example.com/story","preview":{"images":[{"source":{"url":"https://preview.redd.it/cover.jpg"}}]}}"""))
    }
    @Test fun galleryOrderAndAnimatedFilesArePreserved() {
        val result = requireNotNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"two"},{"media_id":"one"}]},"media_metadata":{"one":{"status":"valid","e":"AnimatedImage","s":{"gif":"https://i.redd.it/one.gif","mp4":"https://i.redd.it/one.mp4"}},"two":{"status":"valid","e":"Image","s":{"u":"https://preview.redd.it/two.png?width=1080&amp;format=webp"}}}}"""))
        assertEquals(listOf("https://i.redd.it/two.png", "https://i.redd.it/one.gif"), result.media.map { it.url })
        assertEquals(listOf(1, 2), result.media.map { it.ordinal })
        assertEquals("image_abc123_02.gif", result.media.last().suggestedFileName())
    }
    @Test fun animatedGalleryFallsBackToMp4InsteadOfStillCover() {
        val result = requireNotNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"one"}]},"media_metadata":{"one":{"e":"AnimatedImage","s":{"mp4":"https://i.redd.it/one.mp4","u":"https://i.redd.it/cover.jpg"}}}}"""))
        assertEquals(MediaType.VIDEO, result.media.single().type)
    }
    @Test fun incompleteGalleryDoesNotReturnOnlyACoverOrPartialSelection() {
        assertNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"one"},{"media_id":"missing"}]},"media_metadata":{"one":{"s":{"u":"https://i.redd.it/one.jpg"}}}}"""))
        assertNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"one"}]},"preview":{"images":[{"source":{"url":"https://preview.redd.it/cover.jpg"}}]}}"""))
        assertNull(post("""{"id":"abc123","gallery_data":{"items":[{"media_id":"one"},{"media_id":"two"}]},"media_metadata":{"one":{"s":{"u":"https://i.redd.it/one.jpg"}},"two":{"s":{"u":"https://preview.redd.it/two.jpg?blur=40"}}}}"""))
    }
    @Test fun crosspostKeepsOuterIdentityAndOriginalMedia() {
        val result = requireNotNull(post("""{"id":"outer","title":"My repost","author":"reposter","permalink":"/r/test/comments/outer/title/","crosspost_parent_list":[{"id":"inner","author":"original","url":"https://i.redd.it/photo.jpg"}]}"""))
        assertEquals("outer", result.id)
        assertEquals("outer", result.media.single().postId)
        assertEquals("reposter", result.author)
        assertEquals("My repost", result.text)
    }
    @Test fun deletedAuthorDoesNotExposeCreatorButton() {
        assertNull(requireNotNull(post("""{"id":"abc123","author":"[deleted]","url":"https://i.redd.it/photo.jpg"}""")).creator)
    }
    @Test fun crosspostVideoTrackDataMatchesTheSelectedParent() {
        val raw = JSONObject("""{"id":"outer","crosspost_parent_list":[{"id":"empty"},{"id":"video","secure_media":{"reddit_video":{"fallback_url":"https://v.redd.it/video/DASH_720.mp4","dash_url":"https://v.redd.it/video/DASHPlaylist.mpd"}}}]}""")
        val result = requireNotNull(RedditPostNormalizer.normalizePost(raw, "https://redd.it/outer"))
        assertEquals("https://v.redd.it/video/DASHPlaylist.mpd", requireNotNull(RedditPostNormalizer.videoDataForUrl(raw, result.media.single().url)).optString("dash_url"))
    }
    @Test fun shortGalleryAndShareLinksAreDistinguished() {
        assertEquals("https://www.reddit.com/comments/abc123.json?raw_json=1", RedditLinkParser.jsonEndpoint("https://redd.it/abc123?share=1"))
        assertEquals("abc123", RedditLinkParser.postId("https://www.reddit.com/gallery/abc123"))
        assertTrue(RedditLinkParser.isShareLink("https://www.reddit.com/r/pics/s/TokenAbc"))
        assertNull(RedditLinkParser.postId("https://www.reddit.com/r/pics/s/TokenAbc"))
        assertFalse(RedditLinkParser.isSupported("https://reddit.com.evil.test/comments/abc123"))
        assertFalse(RedditLinkParser.isSupported("https://reddit.com@evil.test/comments/abc123"))
    }
    @Test fun creatorPaginationAdvancesAcrossTextOnlyPages() {
        val creator = CreatorProfile(Platform.REDDIT, "poster", "poster")
        val page = requireNotNull(RedditCreatorPageNormalizer.normalize(JSONObject("""{"data":{"after":"t3_next","children":[{"kind":"t3","data":{"id":"text1","author":"poster","is_self":true}},{"kind":"t3","data":{"id":"other","author":"another","url":"https://i.redd.it/image.jpg"}}]}}"""), creator))
        assertTrue(page.works.isEmpty())
        assertEquals("t3_next", page.nextAfter)
    }
    @Test fun creatorPaginationDoesNotTreatJsonNullAsCursor() {
        val page = requireNotNull(RedditCreatorPageNormalizer.normalize(JSONObject("""{"data":{"after":null,"children":[]}}"""), CreatorProfile(Platform.REDDIT, "poster", "poster")))
        assertEquals("", page.nextAfter)
    }
}
