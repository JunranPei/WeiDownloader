package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuParserTest {
    @Test
    fun webViewJsonPayloadParsesWithoutHtmlScriptTags() {
        val payload = """{"noteId":"5f29440a0000000001005014","title":"Tail video","user":{"userId":"author","nickName":"Creator"},"imageList":[{"url":"https://sns-webpic-qc.xhscdn.com/cover.jpg"}],"video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/video.mp4"}]}}}}"""
        val post = requireNotNull(XiaohongshuPageNormalizer.normalizePayload(payload))
        assertEquals("5f29440a0000000001005014", post.id)
        assertEquals("Tail video", post.title)
        assertEquals(MediaType.VIDEO, post.media.single().type)
    }

    @Test
    fun internalProfileHintIsRemovedWithoutChangingTheNotesOwnToken() {
        val url = "https://www.xiaohongshu.com/explore/5f29440a0000000001005014?xsec_token=own%2Btoken%3D&xsec_source=pc_user"
        assertEquals(url, XiaohongshuLinkParser.noteUrl(url + "&xhs_profile=https%3A%2F%2Fwww.xiaohongshu.com%2Fuser%2Fprofile%2Fauthor%3Fxsec_token%3Dprofile"))
        assertEquals(url, XiaohongshuLinkParser.noteUrl(url))
        assertEquals("https://xhslink.cn/o/2uN5D8VgpIH", XiaohongshuLinkParser.noteUrl("https://xhslink.cn/o/2uN5D8VgpIH"))
    }

    @Test
    fun encodedPlaceholderMustNotBeTruncatedIntoA24CharacterId() {
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":[{"displayTitle":"Placeholder","cover":{"urlPre":"https://example.com/img##c=vp4","fileId":"prefix%%60f9bfde158b4ae9bd0e81e60184bfb4"}}]}}</script>"""
        val creator = CreatorProfile(Platform.XIAOHONGSHU, "user-1", "Alice", "", "https://www.xiaohongshu.com/user/profile/user-1")
        assertTrue(XiaohongshuCreatorPageNormalizer.normalize(html, creator).works.isEmpty())
    }

    @Test
    fun extractsCreatorNotesFromInitialState() {
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[{"noteId":"68cd12340000000001020304","displayTitle":"测试作品","xsecToken":"token+value","cover":{"urlDefault":"http:\u002F\u002Fsns-webpic-qc.xhscdn.com\u002Fcover.webp"}}]]}}}</script>"""
        val creator = CreatorProfile(Platform.XIAOHONGSHU, "user-1", "Alice", "", "https://www.xiaohongshu.com/user/profile/user-1")

        val page = XiaohongshuCreatorPageNormalizer.normalize(html, creator)

        assertEquals(1, page.works.size)
        assertEquals("68cd12340000000001020304", page.works.single().id)
        assertEquals("测试作品", page.works.single().title)
        assertEquals("https://sns-webpic-qc.xhscdn.com/cover.webp", page.works.single().coverUrl)
        assertEquals("https://www.xiaohongshu.com/explore/68cd12340000000001020304?xsec_token=token%2Bvalue&xsec_source=pc_user", page.works.single().sourceUrl)
    }

    @Test
    fun rejects32CharacterProfilePlaceholderWhenCardOmitsItsOwnToken() {
        val id = "60f9bfde158b4ae9bd0e81e60184bfb4"
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[{"id":"$id","displayTitle":"当前作品","cover":{"urlDefault":"https:\u002F\u002Fsns-webpic-qc.xhscdn.com\u002Fcover.webp"}}]]}}}</script>"""
        val creator = CreatorProfile(
            Platform.XIAOHONGSHU,
            "user-1",
            "Alice",
            "",
            "https://www.xiaohongshu.com/user/profile/user-1?xsec_token=profile%2Btoken",
        )

        assertTrue(XiaohongshuCreatorPageNormalizer.normalize(html, creator).works.isEmpty())
    }

    @Test
    fun accepts32CharacterNoteWhenCardCarriesItsOwnToken() {
        val id = "60f9bfde158b4ae9bd0e81e60184bfb4"
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[{"id":"$id","displayTitle":"当前作品","xsecToken":"card+token","cover":{"urlDefault":"https:\u002F\u002Fsns-webpic-qc.xhscdn.com\u002Fcover.webp"}}]]}}}</script>"""
        val creator = CreatorProfile(
            Platform.XIAOHONGSHU,
            "user-1",
            "Alice",
            "",
            "https://www.xiaohongshu.com/user/profile/user-1?xsec_token=profile%2Btoken",
        )

        val work = XiaohongshuCreatorPageNormalizer.normalize(html, creator).works.single()

        assertEquals(id, work.id)
        assertTrue(work.sourceUrl.contains("xsec_token=card%2Btoken"))
    }

    @Test
    fun ignoresProfileCardsWithoutRealNoteIdsInsteadOfInventingClickableSources() {
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[{"id":"","noteCard":{"noteId":"","displayTitle":"卡片标题","cover":{"urlDefault":"https://sns-webpic-qc.xhscdn.com/card.webp"}}}]]}}}</script>"""
        val creator = CreatorProfile(Platform.XIAOHONGSHU, "user-1", "Alice", "", "https://www.xiaohongshu.com/user/profile/user-1")

        val page = XiaohongshuCreatorPageNormalizer.normalize(html, creator)

        assertTrue(page.works.isEmpty())
    }

    @Test
    fun restoresEachNoteIdFromItsOwnEncodedCoverWithoutCrossPairingMetadata() {
        val firstId = "68cd12340000000001020304"
        val secondId = "68cd12340000000005060708"
        val thirdId = "68cd123400000000090a0b0c"
        val html = """<script>window.__INITIAL_STATE__={"user":{"notes":{"value":[[
            {"id":"wrapper-not-note-id-000000000000","noteCard":{"noteId":"","displayTitle":"第一张","cover":{"urlPre":"https://img.example/first.webp##c=vp4","fileId":"file%%$firstId"}}},
            {"id":"wrapper-not-note-id-000001","noteCard":{"noteId":"","displayTitle":"第二张","user":{"avatar":"https://avatar.example/a.jpg#%$secondId"},"cover":{"urlDefault":"https://img.example/second.webp##c=vp6"}}},
            {"id":"wrapper-not-note-id-000002","noteCard":{"noteId":"","displayTitle":"第三张","cover":{"urlDefault":"https://img.example/third.webp##c=vp1","traceId":"trace#$thirdId"}}}
        ]]}}}</script>"""
        val creator = CreatorProfile(Platform.XIAOHONGSHU, "user-1", "Alice", "", "https://www.xiaohongshu.com/user/profile/user-1")

        val works = XiaohongshuCreatorPageNormalizer.normalize(html, creator).works

        assertEquals(listOf(firstId, secondId, thirdId), works.map { it.id })
        assertEquals(listOf("第一张", "第二张", "第三张"), works.map { it.title })
        assertEquals(
            listOf(
                "https://img.example/first.webp",
                "https://img.example/second.webp",
                "https://img.example/third.webp",
            ),
            works.map { it.coverUrl },
        )
        assertEquals(
            listOf(firstId, secondId, thirdId).map { "https://www.xiaohongshu.com/explore/$it" },
            works.map { it.sourceUrl },
        )
    }

    @Test
    fun extractsXhslinkCnFromCompleteShareText() {
        val share = "文淇 ｜野兽派青春代言人 迎来金黄色的梧桐之秋 赴一... https://xhslink.cn/o/5M7H4h4JQkn Copy and open rednote to view the note"

        assertEquals("https://xhslink.cn/o/5M7H4h4JQkn", XiaohongshuLinkParser.extractUrl(share))
        assertTrue(XiaohongshuLinkParser.isSupported(share))
    }

    @Test
    fun usesUnwatermarkedOriginalAndKeepsWebImageAsThumbnail() {
        val html = "<script>window.__SETUP_SERVER_STATE__={\"data\":{\"noteData\":{\"noteId\":\"6aaab10b000000002902ecba\",\"desc\":\"梧桐之秋\",\"user\":{\"nickName\":\"東申未来\",\"userId\":\"6213830b\"},\"imageList\":[{\"width\":2560,\"height\":3840,\"fileId\":\"notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48\",\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"http:\\u002F\\u002Fsns-webpic-qc.xhscdn.com\\u002Fphoto!h5_1080jpg\"}]}]}}}</script>"

        val post = requireNotNull(XiaohongshuPageNormalizer.normalize(html))

        assertEquals("6aaab10b000000002902ecba", post.id)
        assertEquals("東申未来", post.author)
        assertEquals(1, post.media.size)
        val media = post.media.single()
        assertEquals("https://ci.xiaohongshu.com/notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48", media.url)
        assertEquals("https://sns-webpic-qc.xhscdn.com/photo!h5_1080jpg", media.thumbnailUrl)
        assertEquals(4, media.variants.size)
        assertEquals(
            listOf(
                "https://sns-img-bd.xhscdn.com/notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48",
                "https://sns-img-qc.xhscdn.com/notes_pre_post/1040g3k03256c9q6lk4005ogjgc5ocmhlrj93a48",
                "https://sns-webpic-qc.xhscdn.com/photo!h5_1080jpg",
            ),
            media.fallbackUrls,
        )
        assertEquals(2560, media.variants.first().width)
        assertEquals(3840, media.variants.first().height)
        assertEquals("image_6aaab10b000000002902ecba_01.jpg", media.suggestedFileName())
    }

    @Test
    fun prefersTraceIdWhenTheImageFileIdIsWatermarked() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"trace-note\",\"imageList\":[{\"width\":1440,\"height\":1920,\"fileId\":\"watermarked-id\",\"traceId\":\"clean-id\",\"urlDefault\":\"https://sns-webpic-qc.xhscdn.com/202610/clean-id!nd_dft_wlteh_webp_3\"}]}}</script>"

        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()

        assertEquals("https://ci.xiaohongshu.com/clean-id", media.url)
        assertTrue(media.variants.any { it.url == "https://sns-img-bd.xhscdn.com/clean-id" })
        assertTrue(media.variants.any { it.key == "web" && it.url.contains("sns-webpic") })
    }

    @Test
    fun fallsBackToWebImageWhenFileIdIsMissing() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"fallback\",\"imageList\":[{\"width\":1080,\"height\":1440,\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/fallback.webp\"}]}]}}</script>"

        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()

        assertEquals("https://sns-webpic-qc.xhscdn.com/fallback.webp", media.url)
        assertEquals("web", media.selectedVariantKey)
        assertEquals(0, media.variants.single().width)
        assertEquals(0, media.variants.single().height)
    }

    @Test
    fun videoUsesWebPreviewInsteadOfOriginalImageAsThumbnail() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"video-note\",\"imageList\":[{\"width\":2560,\"height\":3840,\"fileId\":\"notes_pre_post/original-cover\",\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/web-cover.jpg\"}]}],\"video\":{\"media\":{\"stream\":{\"h264\":[{\"masterUrl\":\"https://sns-video-qc.xhscdn.com/video.mp4\"}]}}}}}</script>"

        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()

        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://sns-webpic-qc.xhscdn.com/web-cover.jpg", media.thumbnailUrl)
        assertTrue(media.fallbackUrls.isEmpty())
    }

    @Test
    fun videoFieldMayBeArrayOrDirectMasterUrl() {
        val html = "<script>window.__INITIAL_STATE__={\"note\":{\"noteId\":\"video-array\",\"imageList\":[{\"infoList\":[{\"imageScene\":\"H5_DTL\",\"url\":\"https://sns-webpic-qc.xhscdn.com/cover.jpg\"}]}],\"video\":[{\"masterUrl\":\"https://sns-video-qc.xhscdn.com/path/master.m3u8\"}]}}</script>"
        val media = requireNotNull(XiaohongshuPageNormalizer.normalize(html)).media.single()
        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://sns-video-qc.xhscdn.com/path/master.m3u8", media.url)
    }

    @Test
    fun preservesMultipleVideoAttachmentsInsteadOfCollapsingToTheFirst() {
        val payload = """{"noteId":"multi-video","video":[
            {"id":"one","media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/one.mp4"}]}}},
            {"id":"two","media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/two.mp4"}]}}}
        ]}"""

        val media = requireNotNull(XiaohongshuPageNormalizer.normalizePayload(payload)).media

        assertEquals(2, media.size)
        assertEquals(listOf("https://sns-video-qc.xhscdn.com/one.mp4", "https://sns-video-qc.xhscdn.com/two.mp4"), media.map { it.url })
        assertEquals(listOf(1, 2), media.map { it.ordinal })
    }

    @Test
    fun ordersVideoSourcesDeterministicallyAndKeepsFallbacks() {
        val payload = """{"noteId":"video-sources","video":{
            "consumer":{"originVideoKey":"origin/key.mp4"},
            "media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/master.mp4","backupUrls":["https://sns-bak-qc.xhscdn.com/backup.mp4"]}]}}
        }}"""

        val media = requireNotNull(XiaohongshuPageNormalizer.normalizePayload(payload)).media.single()

        assertEquals("https://sns-video-bd.xhscdn.com/origin/key.mp4", media.url)
        assertEquals(
            listOf(
                "https://sns-video-qc.xhscdn.com/master.mp4",
                "https://sns-bak-qc.xhscdn.com/backup.mp4",
            ),
            media.fallbackUrls,
        )
    }

    @Test
    fun carriesVideoDimensionsFromContainerToNestedUrls() {
        val payload = """{"noteId":"video-dimensions","video":{
            "width":1082,"height":720,
            "media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/video.mp4"}]}}
        }}"""

        val media = requireNotNull(XiaohongshuPageNormalizer.normalizePayload(payload)).media.single()

        assertEquals(1082, media.variants.single().width)
        assertEquals(720, media.variants.single().height)
    }

    @Test
    fun retainsGalleryImagesWhenASeparateVideoIsAlsoPresent() {
        val payload = """{"noteId":"mixed-note","imageList":[
            {"fileId":"image-one","url":"https://sns-webpic-qc.xhscdn.com/one.jpg"},
            {"fileId":"image-two","url":"https://sns-webpic-qc.xhscdn.com/two.jpg"}
        ],"video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video-qc.xhscdn.com/video.mp4"}]}}}}"""

        val media = requireNotNull(XiaohongshuPageNormalizer.normalizePayload(payload)).media

        assertEquals(3, media.size)
        assertEquals(listOf(MediaType.VIDEO, MediaType.IMAGE, MediaType.IMAGE), media.map { it.type })
    }
}
