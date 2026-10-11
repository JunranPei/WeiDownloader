package cn.weibo.filedownloader

import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** Desktop DASH requests expose the actual tracks available to the current session. */
internal object BilibiliPlayRequest {
    fun parameters(bvid: String, aid: Long, cid: Long): Map<String, String> = buildMap {
        if (bvid.isNotBlank()) put("bvid", bvid) else put("avid", aid.toString())
        put("cid", cid.toString())
        put("qn", "127")
        put("fnver", "0")
        put("fnval", "4048")
        put("fourk", "1")
    }
}

internal object BilibiliWbiSigner {
    private val permutation = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )

    fun mixinKey(nav: JSONObject): String? {
        val images = nav.optJSONObject("data")?.optJSONObject("wbi_img") ?: return null
        val raw = listOf("img_url", "sub_url").joinToString("") {
            images.optString(it).substringAfterLast('/').substringBefore('.')
        }
        if (raw.length != 64) return null
        return permutation.take(32).map { raw[it] }.joinToString("")
    }

    fun sign(parameters: Map<String, String>, key: String, timestamp: Long): Map<String, String> {
        val values = (parameters + ("wts" to timestamp.toString())).toSortedMap()
            .mapValues { (_, value) -> value.filterNot { it in "!'()*" } }
        val query = values.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val digest = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return values + ("w_rid" to digest)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** Lists returned streams, never support_formats entries without a downloadable URL. */
internal object BilibiliDashParser {
    fun variants(data: JSONObject, descriptions: Map<Int, String> = emptyMap()): List<MediaVariant> {
        val dash = data.optJSONObject("dash") ?: return emptyList()
        val audios = dash.optJSONArray("audio")
        val audio = (0 until (audios?.length() ?: 0)).mapNotNull { audios?.optJSONObject(it) }
            .filter { sourceUrl(it).isNotBlank() && it.optString("codecs").startsWith("mp4a", true) }
            .maxByOrNull { it.optLong("bandwidth") }
        // A video with audio must retain that audio; unsupported audio uses the progressive fallback.
        if ((audios?.length() ?: 0) > 0 && audio == null) return emptyList()
        val videos = dash.optJSONArray("video") ?: return emptyList()
        return (0 until videos.length()).mapNotNull(videos::optJSONObject)
            .filter { it.optInt("id") > 0 && sourceUrl(it).isNotBlank() && codecRank(it) < 2 }
            .groupBy { it.optInt("id") }
            .toSortedMap(compareByDescending { it })
            .map { (quality, sources) ->
                // Keep the highest quality; prefer AVC within that quality for device compatibility.
                val source = sources.sortedWith(compareBy<JSONObject> { codecRank(it) }
                    .thenByDescending { it.optLong("bandwidth") }).first()
                MediaVariant(
                    key = descriptions[quality] ?: qualityLabel(quality),
                    url = sourceUrl(source),
                    width = source.optInt("width"),
                    height = source.optInt("height"),
                    bitrate = source.optLong("bandwidth"),
                    audioUrl = audio?.let(::sourceUrl).orEmpty(),
                )
            }
    }

    fun qualityLabel(quality: Int): String = when (quality) {
        127 -> "8K"
        126 -> "Dolby Vision"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        100 -> "智能修复"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "Q$quality"
    }

    private fun codecRank(source: JSONObject): Int = when {
        source.optString("codecs").startsWith("avc", true) -> 0
        source.optString("codecs").startsWith("hev", true) ||
            source.optString("codecs").startsWith("hvc", true) -> 1
        else -> 2
    }

    private fun sourceUrl(source: JSONObject): String {
        val url = source.optString("baseUrl").ifBlank { source.optString("base_url") }
            .ifBlank { source.optString("url") }
            .ifBlank { source.optJSONArray("backupUrl")?.optString(0).orEmpty() }
            .ifBlank { source.optJSONArray("backup_url")?.optString(0).orEmpty() }
        return if (url.startsWith("//")) "https:$url" else url
    }
}
