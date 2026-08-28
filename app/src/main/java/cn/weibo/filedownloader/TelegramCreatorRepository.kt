package cn.weibo.filedownloader

import android.content.Context

internal data class TelegramCreatorPage(
    val creator: CreatorProfile,
    val works: List<DouyinCreatorWork>,
    val nextBefore: String,
    val hasMore: Boolean,
)

internal class TelegramCreatorRepository(context: Context) {
    private val http = TelegramHttpClient(context)

    fun loadPage(creator: CreatorProfile, before: String): TelegramCreatorPage {
        require(creator.platform == Platform.TELEGRAM && creator.id.isNotBlank())
        val address = buildString {
            append("https://t.me/s/")
            append(creator.id)
            if (before.isNotBlank()) append("?before=").append(before)
        }
        val html = http.requestText(address)
        val resolvedCreator = creator.copy(
            name = TelegramHtmlParser.channelName(html, creator.name),
            avatarUrl = TelegramHtmlParser.channelAvatar(html).ifBlank { creator.avatarUrl },
            profileUrl = "https://t.me/${creator.id}",
        )
        val works = TelegramHtmlParser.splitChannelMessages(html, creator.id).mapNotNull { (target, chunk) ->
            val post = TelegramHtmlParser.parsePost(chunk, target)
            if (post.media.isEmpty()) return@mapNotNull null
            DouyinCreatorWork(
                id = post.id,
                title = post.text,
                coverUrl = post.media.firstNotNullOfOrNull { media ->
                    media.thumbnailUrl.ifBlank { media.url.takeIf { media.type == MediaType.IMAGE }.orEmpty() }
                        .takeIf(String::isNotBlank)
                }.orEmpty(),
                sourceUrl = target.canonicalUrl,
                mediaCount = post.media.size,
                isGallery = post.media.size > 1 || post.media.all { it.type == MediaType.IMAGE },
            )
        }.distinctBy { it.id }
        val nextBefore = TelegramHtmlParser.nextBefore(html, creator.id)
        return TelegramCreatorPage(resolvedCreator, works, nextBefore, nextBefore.isNotBlank())
    }
}
