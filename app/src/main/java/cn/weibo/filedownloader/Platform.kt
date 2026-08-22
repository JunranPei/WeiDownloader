package cn.weibo.filedownloader

enum class Platform(
    val key: String,
    val shortName: String,
    val prefixColor: Int,
    val actionColor: Int,
    val onActionColor: Int,
) {
    WEIBO(
        "weibo",
        "Wei",
        R.color.platform_weibo,
        R.color.platform_weibo,
        R.color.platform_weibo_on_action,
    ),
    DOUYIN(
        "douyin",
        "Dou",
        R.color.platform_douyin_cyan,
        R.color.platform_douyin_black,
        R.color.platform_douyin_on_action,
    ),
}
