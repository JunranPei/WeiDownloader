package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramParserTest {
    @Test
    fun mixedAlbumKeepsVideoBeforePhotoAndInterleavedOrder() {
        val html = """
            <a class="tgme_widget_message_video_player"><video src="https://cdn.example/first.mp4"></video></a>
            <a class="tgme_widget_message_photo_wrap" style="background-image:url('https://cdn.example/second.jpg')"></a>
            <a class="tgme_widget_message_video_player"><video src="https://cdn.example/third.mp4"></video></a>
        """.trimIndent()
        val media = TelegramHtmlParser.parseMedia(html, "telegram-459")
        assertEquals(listOf(MediaType.VIDEO, MediaType.IMAGE, MediaType.VIDEO), media.map { it.type })
        assertEquals(listOf("https://cdn.example/first.mp4", "https://cdn.example/second.jpg", "https://cdn.example/third.mp4"), media.map { it.url })
        assertEquals(listOf(1, 2, 3), media.map { it.ordinal })
    }

    @Test
    fun parsesPublicPostLinks() {
        assertEquals(
            TelegramPostTarget("telegram", 459),
            TelegramLinkParser.targetFromUrl("https://t.me/telegram/459?single"),
        )
        assertEquals(
            TelegramPostTarget("telegram", 457),
            TelegramLinkParser.targetFromUrl("https://telegram.t.me/457"),
        )
        assertFalse(TelegramLinkParser.isSupported("https://t.me/c/123456/7"))
    }

    @Test
    fun parsesPhotoAndVideoFromOfficialWidgetMarkup() {
        val html = """
            <div class="tgme_widget_message" data-post="telegram/459">
              <i class="tgme_widget_message_user_photo"><img src="//cdn.example/avatar.jpg"></i>
              <a class="tgme_widget_message_owner_name"><span>Telegram News</span></a>
              <a class="tgme_widget_message_photo_wrap" style="width:800px;background-image:url('https://cdn.example/image.jpg')">
                <div class="tgme_widget_message_photo" style="padding-top:75%"></div>
              </a>
              <a class="tgme_widget_message_video_player">
                <i class="tgme_widget_message_video_thumb" style="background-image:url('https://cdn.example/thumb.jpg')"></i>
                <div class="tgme_widget_message_video_wrap" style="width:1920px;padding-top:56.25%">
                  <video src="https://cdn.example/video.mp4?token=abc"></video>
                </div>
              </a>
              <div class="tgme_widget_message_text"><b>Hello</b><br>world &amp; friends</div>
            </div>
        """.trimIndent()

        val post = TelegramHtmlParser.parsePost(html, TelegramPostTarget("telegram", 459))

        assertEquals("Telegram News", post.author)
        assertEquals("Hello\nworld & friends", post.text)
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), post.media.map { it.type })
        assertEquals(800, post.media[0].variants.single().width)
        assertEquals(600, post.media[0].variants.single().height)
        assertEquals(1080, post.media[1].variants.single().height)
        assertEquals("https://cdn.example/thumb.jpg", post.media[1].thumbnailUrl)
        assertTrue(post.media[1].url.contains("token=abc"))
    }
}
