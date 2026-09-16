package cn.weibo.filedownloader

enum class Platform(
    val key: String,
    val shortName: String,
    val downloadFolderName: String,
    val prefixColor: Int,
    val actionColor: Int,
    val onActionColor: Int,
    val enabled: Boolean = true,
) {
    WEIBO(
        "weibo",
        "Wei",
        "Weibo",
        R.color.platform_weibo,
        R.color.platform_weibo,
        R.color.platform_weibo_on_action,
    ),
    DOUYIN(
        "douyin",
        "Dou",
        "Douyin",
        R.color.platform_douyin_cyan,
        R.color.platform_douyin_black,
        R.color.platform_douyin_on_action,
    ),
    BILIBILI(
        "bilibili",
        "Bili",
        "Bilibili",
        R.color.platform_bilibili,
        R.color.platform_bilibili,
        R.color.platform_bilibili_on_action,
    ),
    TELEGRAM(
        "telegram",
        "Tele",
        "Telegram",
        R.color.platform_telegram,
        R.color.platform_telegram,
        R.color.platform_telegram_on_action,
        false,
    ),
    X(
        "x",
        "X",
        "X",
        R.color.platform_x,
        R.color.platform_x,
        R.color.platform_x_on_action,
    ),
    INSTAGRAM(
        "instagram",
        "Insta",
        "Instagram",
        R.color.platform_instagram,
        R.color.platform_instagram,
        R.color.platform_instagram_on_action,
    ),
    XIAOHONGSHU(
        "xiaohongshu",
        "Red",
        "Xiaohongshu",
        R.color.platform_xiaohongshu,
        R.color.platform_xiaohongshu,
        R.color.platform_xiaohongshu_on_action,
    ),
    REDDIT(
        "reddit",
        "Reddit",
        "Reddit",
        R.color.platform_reddit,
        R.color.platform_reddit,
        R.color.platform_reddit_on_action,
    ),

    ;

    companion object {
        val enabledEntries: List<Platform> by lazy { entries.filter(Platform::enabled) }
    }
}
