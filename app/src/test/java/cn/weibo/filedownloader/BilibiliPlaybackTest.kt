package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BilibiliPlaybackTest {
    @Test fun requestsDesktopDashAndHighestAllowedQuality() {
        val query = BilibiliPlayRequest.parameters("BV1V8af6zEKA", 0, 123)
        assertEquals("4048", query["fnval"])
        assertEquals("127", query["qn"])
        assertEquals("1", query["fourk"])
        assertFalse(query.containsKey("platform"))
        assertFalse(query.containsKey("try_look"))
        assertEquals("123", query["cid"])
        assertEquals("42", BilibiliPlayRequest.parameters("", 42, 123)["avid"])
    }

    @Test fun matchesFixedWbiSignatureVector() {
        val nav = JSONObject("""{"data":{"wbi_img":{
            "img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
            "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
        }}}""")
        val key = requireNotNull(BilibiliWbiSigner.mixinKey(nav))
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        val signed = BilibiliWbiSigner.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169)
        assertEquals("6149fdadf571698ca7e6a567265cd0ee", signed["w_rid"])
        assertEquals("1702204169", signed["wts"])
        assertNull(BilibiliWbiSigner.mixinKey(JSONObject("{}")))
    }

    @Test fun doesNotInventAdvertisedButLockedQualities() {
        val data = JSONObject("""{
            "accept_quality":[120,116,80,64],
            "dash":{"video":[{"id":80,"baseUrl":"https://video/1080.m4s",
                "width":1920,"height":1080,"codecs":"avc1.640028","bandwidth":2400000}],
                "audio":[{"baseUrl":"https://audio/track.m4s","codecs":"mp4a.40.2","bandwidth":192000}]}}
        """)
        val variants = BilibiliDashParser.variants(data)
        assertEquals(listOf("1080P"), variants.map { it.key })
        assertEquals(1920, variants.single().width)
        assertEquals(1080, variants.single().height)
        assertEquals("https://audio/track.m4s", variants.single().audioUrl)
    }

    @Test fun keepsHigherHevcQualityAndPrefersAvcWithinSameQuality() {
        val data = JSONObject("""{"dash":{"video":[
            {"id":80,"baseUrl":"https://video/1080-hevc","codecs":"hev1.1.6.L120","bandwidth":4000000},
            {"id":80,"baseUrl":"https://video/1080-avc","codecs":"avc1.640028","bandwidth":3000000},
            {"id":120,"baseUrl":"https://video/4k-hevc","codecs":"hev1.1.6.L150","width":3840,"height":2160},
            {"id":120,"baseUrl":"https://video/4k-av1","codecs":"av01.0.12M.08","bandwidth":8000000},
            {"id":64,"base_url":"https://video/720","codecs":"avc1.640020","width":1280,"height":720}],
            "audio":[{"baseUrl":"https://audio/low","codecs":"mp4a.40.2","bandwidth":64000},
                     {"base_url":"https://audio/high","codecs":"mp4a.40.2","bandwidth":192000}]}}""")
        val variants = BilibiliDashParser.variants(data)
        assertEquals(listOf("4K", "1080P", "720P"), variants.map { it.key })
        assertEquals("https://video/4k-hevc", variants[0].url)
        assertEquals("https://video/1080-avc", variants[1].url)
        assertTrue(variants.all { it.audioUrl == "https://audio/high" })
    }

    @Test fun ignoresEmptySourcesAndAcceptsBackupUrls() {
        val data = JSONObject("""{"dash":{"video":[
            {"id":120,"codecs":"avc1.640033"},
            {"id":80,"backup_url":["//video/1080.m4s"],"codecs":"avc1.640028"}],
            "audio":[{"backupUrl":["//audio/a.m4s"],"codecs":"mp4a.40.2"}]}}""")
        val variant = BilibiliDashParser.variants(data).single()
        assertEquals("1080P", variant.key)
        assertEquals("https://video/1080.m4s", variant.url)
        assertEquals("https://audio/a.m4s", variant.audioUrl)
    }

    @Test fun preservesAndClearsAudioWhenSelectingVariants() {
        val high = MediaVariant("1080P", "https://video/1080.m4s", audioUrl = "https://audio/high.m4s")
        val low = MediaVariant("720P", "https://video/720.mp4")
        val item = MediaItem(MediaType.VIDEO, low.url, "720P", "BV1V8af6zEKA", 1,
            variants = listOf(high, low), selectedVariantKey = low.key)
        val selected = item.selectVariant(high.key)
        assertEquals(high.url, selected.url)
        assertEquals(high.audioUrl, selected.audioUrl)
        assertEquals("video_BV1V8af6zEKA_01.mp4", selected.suggestedFileName())
        assertEquals("", selected.selectVariant(low.key).audioUrl)
    }

    @Test fun doesNotLoseUnsupportedAudioSilently() {
        val data = JSONObject("""{"dash":{"video":[
            {"id":80,"baseUrl":"https://video/v.m4s","codecs":"avc1.640028"}],
            "audio":[{"baseUrl":"https://audio/a.m4s","codecs":"ec-3"}]}}""")
        assertTrue(BilibiliDashParser.variants(data).isEmpty())
    }
}
