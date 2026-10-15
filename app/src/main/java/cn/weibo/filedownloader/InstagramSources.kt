package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI

internal object InstagramRequests {
    const val WEB_APP_ID = "936619743392459"
    const val MOBILE_APP_ID = "124024574287414"
    const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    const val MOBILE_USER_AGENT = "Instagram 361.0.0.35.82 (iPad13,8; iOS 18_0; en_US; en-US; " +
        "scale=2.00; 2048x2732; 674117118) AppleWebKit/420+"

    fun mediaId(shortcode: String): String {
        require(shortcode.length in 1..11)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return shortcode.fold(BigInteger.ZERO) { value, char ->
            val digit = alphabet.indexOf(char)
            require(digit >= 0)
            value.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
        }.toString()
    }

    /** Keep browser sizing cookies out of the native API identity, while preserving authentication. */
    fun mobileCookie(cookie: String): String = cookie.split(';').map(String::trim)
        .filter { it.isNotBlank() && it.substringBefore('=') !in setOf("wd", "dpr", "ig_vw", "ig_pr") }
        .joinToString("; ")

    fun cookieValue(cookie: String, name: String): String = cookie.split(';')
        .firstOrNull { it.trim().substringBefore('=') == name }?.trim()?.substringAfter('=', "").orEmpty()

    fun hasSession(cookie: String): Boolean = cookieValue(cookie, "sessionid").isNotBlank()

    fun isInstagramUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in setOf("instagram.com", "www.instagram.com", "i.instagram.com")
    }.getOrDefault(false)

    /** Preserve signed query parameters verbatim; only remove Instagram's documented se size limit. */
    fun withoutSizeLimit(url: String): String = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        if (uri.scheme != "https" || !(host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))) return url
        val query = uri.rawQuery ?: return url
        val parameters = query.split('&')
        val kept = parameters.filterNot { Regex("se=\\d+").matches(it) }
        if (parameters.size == kept.size) return url
        url.substringBefore('?') + kept.joinToString("&").let { if (it.isBlank()) "" else "?$it" } +
            uri.rawFragment?.let { "#$it" }.orEmpty()
    }.getOrDefault(url)
}

internal object InstagramPostSources {
    enum class Source { MOBILE, WEB, SHORTCODE }
    data class Result(val payload: JSONObject, val mobileAttempted: Boolean, val mobileSucceeded: Boolean)

    fun load(target: InstagramPostTarget, cookie: String, request: (Source) -> JSONObject): Result {
        val payloads = mutableListOf<JSONObject>()
        var lastError: Exception? = null
        fun fetch(source: Source): Boolean = try {
            val payload = request(source)
            val raw = item(payload)
            if (raw != null && matches(raw, target) && media(raw).any { child ->
                    candidateList(child.optJSONObject("image_versions2")?.optJSONArray("candidates")).isNotEmpty() ||
                        candidateList(child.optJSONArray("video_versions")).isNotEmpty()
                }) { payloads += payload; true } else false
        } catch (error: Exception) { lastError = error; false }

        val hasSession = InstagramRequests.hasSession(cookie)
        val mobileSucceeded = hasSession && fetch(Source.MOBILE)
        fetch(Source.WEB)
        if (payloads.isEmpty()) fetch(Source.SHORTCODE)
        val payload = merge(payloads, target) ?: throw lastError ?: InstagramException("Instagram returned no media")
        return Result(payload, hasSession, mobileSucceeded)
    }

    fun item(payload: JSONObject): JSONObject? = payload.optJSONObject("data")
        ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")?.optJSONArray("items")?.optJSONObject(0)
        ?: payload.optJSONArray("items")?.optJSONObject(0)

    fun media(item: JSONObject): List<JSONObject> = item.optJSONArray("carousel_media")
        ?.takeIf { it.length() > 0 }?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } ?: listOf(item)

    fun matches(item: JSONObject, target: InstagramPostTarget): Boolean {
        val code = item.optString("code")
        if (code.isNotBlank() && code != target.shortcode) return false
        val pk = identity(item)
        val expected = runCatching { InstagramRequests.mediaId(target.shortcode) }.getOrNull()
        return code == target.shortcode || pk.isNotBlank() && pk == expected
    }

    fun merge(payloads: List<JSONObject>, target: InstagramPostTarget): JSONObject? {
        val items = payloads.mapNotNull(::item).filter { matches(it, target) }
        val base = items.maxByOrNull { media(it).size } ?: return null
        val merged = JSONObject(base.toString())
        val baseMedia = media(base)
        val combined = baseMedia.mapIndexed { index, raw ->
            val id = identity(raw)
            val peers = items.mapNotNull { parent ->
                val children = media(parent)
                if (id.isNotBlank()) children.firstOrNull { identity(it) == id }
                else children.getOrNull(index)?.takeIf { identity(it).isBlank() && children.size == baseMedia.size }
            }
            mergeMedia(raw, peers)
        }
        if (combined.isEmpty()) return null
        if (base.optJSONArray("carousel_media") != null) merged.put("carousel_media", JSONArray(combined))
        else return JSONObject().put("items", JSONArray().put(combined.single()))
        return JSONObject().put("items", JSONArray().put(merged))
    }

    private fun mergeMedia(base: JSONObject, peers: List<JSONObject>): JSONObject {
        val result = JSONObject(base.toString())
        val images = peers.flatMap { candidateList(it.optJSONObject("image_versions2")?.optJSONArray("candidates")) }
            .distinctBy { it.optString("url") }
        result.put("image_versions2", JSONObject().put("candidates", JSONArray(images)))
        val videos = peers.flatMap { candidateList(it.optJSONArray("video_versions")) }.distinctBy { it.optString("url") }
        if (videos.isNotEmpty()) result.put("video_versions", JSONArray(videos))
        peers.maxByOrNull { it.optLong("original_width") * it.optLong("original_height") }?.let {
            result.put("original_width", it.optInt("original_width"))
                .put("original_height", it.optInt("original_height"))
        }
        return result
    }

    fun candidateList(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun identity(item: JSONObject): String = item.optString("pk").ifBlank { item.optString("id") }
        .substringBefore('_')
}

internal object InstagramImageQuality {
    data class Dimensions(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height
    }

    fun enhance(payload: JSONObject, probe: (String) -> Dimensions?): JSONObject {
        val result = JSONObject(payload.toString())
        val item = InstagramPostSources.item(result) ?: return result
        for (raw in InstagramPostSources.media(item)) {
            if (raw.optInt("media_type") == 2 || (raw.optJSONArray("video_versions")?.length() ?: 0) > 0) continue
            val array = raw.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: continue
            val visited = mutableSetOf<String>()
            // If an alleged original turns out to be smaller, verify the next largest candidate too.
            for (attempt in 0 until 3) {
                val best = InstagramPostSources.candidateList(array)
                    .maxByOrNull { it.optLong("width") * it.optLong("height") } ?: break
                val url = best.optString("url")
                if (!visited.add(url)) break
                val actual = runCatching { probe(url) }.getOrNull()
                if (actual != null) best.put("width", actual.width).put("height", actual.height).put("dimensions_verified", true)
                val unlimited = InstagramRequests.withoutSizeLimit(url)
                if (unlimited != url) {
                    val size = runCatching { probe(unlimited) }.getOrNull()
                    // A changed URL is a candidate only after its actual image dimensions were read.
                    if (size != null && size.area >= (actual?.area ?: best.optLong("width") * best.optLong("height"))) {
                        array.put(JSONObject().put("url", unlimited).put("width", size.width).put("height", size.height)
                            .put("dimensions_verified", true))
                        visited += unlimited
                    }
                }
            }
        }
        return result
    }

    fun reachesOriginal(width: Int, height: Int, originalWidth: Int, originalHeight: Int): Boolean =
        originalWidth > 0 && originalHeight > 0 && width >= originalWidth - 1 && height >= originalHeight - 1
}
