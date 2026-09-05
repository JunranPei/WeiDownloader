package cn.weibo.filedownloader

enum class Platform(
    val key: String,
    val shortName: String,
    val downloadFolderName: String,
    val prefixColor: Int,
    val actionColor: Int,
    val onActionColor: Int,
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
    ),
    X(
        "x",
        "X",
        "X",
        R.color.platform_x,
        R.color.platform_x,
        R.color.platform_x_on_action,
    ),
}
