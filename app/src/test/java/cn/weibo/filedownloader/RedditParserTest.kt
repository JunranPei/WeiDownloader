package cn.weibo.filedownloader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditParserTest {
    @Test
    fun extractsLinkFromShareTextAndBuildsJsonEndpoint() {
        val share = "Plaza de España https://www.reddit.com/r/pics/comments/1s6d7rv/plaza_de_espana/?utm_source=share Copy Reddit link"

        assertTrue(RedditLinkParser.isSupported(share))
        assertEquals(
            "https://www.reddit.com/r/pics/comments/1s6d7rv/plaza_de_espana.json?raw_json=1",
            RedditLinkParser.jsonEndpoint(share),
        )
    }

    @Test
    fun parsesPublicRedditImagePost() {
        val payload = JSONArray(
            """[{"data":{"children":[{"data":{"id":"1s6d7rv","title":"Plaza de España | Sevilla, Spain","author":"nevernudedude","permalink":"/r/pics/comments/1s6d7rv/plaza/","preview":{"images":[{"source":{"url":"https://i.redd.it/106a9wthaprg1.jpeg?width=2048&amp;format=pjpg","width":2048,"height":1365}}]}}}]}}]""",
        )

        val post = requireNotNull(RedditPostNormalizer.normalize(payload, "https://redd.it/1s6d7rv"))

        assertEquals("1s6d7rv", post.id)
        assertEquals("nevernudedude", post.author)
        assertEquals(1, post.media.size)
        assertEquals(MediaType.IMAGE, post.media.single().type)
        assertEquals(
            "https://i.redd.it/106a9wthaprg1.jpeg?width=2048&format=pjpg",
            post.media.single().url,
        )
    }

    @Test
    fun galleryDoesNotAddPreviewCoverAsDuplicateMedia() {
        val payload = JSONArray(
            """[{"data":{"children":[{"data":{"id":"gallery1","title":"Gallery","author":"poster","gallery_data":{"items":[{"media_id":"one"},{"media_id":"two"}]},"media_metadata":{"one":{"s":{"u":"https://i.redd.it/one.jpg","x":1200,"y":800}},"two":{"s":{"u":"https://i.redd.it/two.jpg","x":1200,"y":800}}},"preview":{"images":[{"source":{"url":"https://preview.redd.it/cover.jpg"}}]}}}]}}]""",
        )

        val post = requireNotNull(RedditPostNormalizer.normalize(payload, "https://redd.it/gallery1"))

        assertEquals(2, post.media.size)
        assertTrue(post.media.all { it.type == MediaType.IMAGE })
    }
}
