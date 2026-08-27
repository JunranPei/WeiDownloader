package cn.weibo.filedownloader

internal object ActiveDownloadPostCache {
    private const val MAX_ENTRIES = 20
    private val values = LinkedHashMap<String, WeiboPost>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun put(workKey: String, post: WeiboPost) {
        values[workKey] = post
        while (values.size > MAX_ENTRIES) values.remove(values.keys.first())
    }

    @Synchronized
    fun get(workKey: String): WeiboPost? = values[workKey]
}
