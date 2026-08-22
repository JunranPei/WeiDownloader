package cn.weibo.carousel

/** A UI-only media item. The host app owns download state and persists it. */
data class CarouselMediaItem(
    val id: String,
    val imageUrl: String,
    val fallbackImageUrl: String = "",
    val title: CharSequence,
    val subtitle: CharSequence,
    val selected: Boolean = false,
    val selectable: Boolean = true,
    val downloaded: Boolean = false,
)
