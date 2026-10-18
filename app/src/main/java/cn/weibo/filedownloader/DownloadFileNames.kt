package cn.weibo.filedownloader

import java.io.IOException

/** Finalization is serialized so parallel retries cannot replace an existing successful download. */
internal object DownloadFileNames {
    fun available(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        for (index in 1..10_000) {
            val candidate = "$stem ($index)$extension"
            if (!exists(candidate)) return candidate
        }
        throw IOException("No available download filename")
    }
}
