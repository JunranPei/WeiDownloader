package cn.weibo.filedownloader

enum class Platform(
    val key: String,
    val shortName: String,
    val accentColor: Int,
) {
    WEIBO("weibo", "Wei", R.color.platform_weibo),
    DOUYIN("douyin", "Dou", R.color.platform_douyin),
}

