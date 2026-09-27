package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object XiaohongshuCreatorPageNormalizer {
    fun normalize(html: String, creator: CreatorProfile): DouyinCreatorPage {
        val state = extractAssignedJson(html, "window.__INITIAL_STATE__")
            ?: extractAssignedJson(html, "__INITIAL_STATE__")
        val notes = (state?.optJSONObject("user")?.opt("notes") ?: findNamedValue(state, "notes")).unwrapVueValue()
        val works = linkedMapOf<String, DouyinCreatorWork>()
        collectNotes(notes, works, creator.profileUrl)
        if (works.isEmpty()) {
            // SSR leaves user.notes as empty placeholders. The rendered
            // author cards are nested in one of several state branches, so do
            // not take the first key named "feeds" (it is often an unrelated
            // empty home feed); walk the whole state and bind each cover to
            // its own noteCard object.
            collectFeedCards(state, works, creator.profileUrl)
        }
        if (works.isEmpty()) collectRawProfileCards(html, works, creator.profileUrl)
        if (works.isEmpty()) collectLegacyLinks(html, works)
        return DouyinCreatorPage(creator, works.values.toList(), 0L, false)
    }

    private fun collectNotes(value: Any?, result: LinkedHashMap<String, DouyinCreatorWork>, profileUrl: String) {
        when (value) {
            is JSONObject -> {
                val rawId = value.optString("noteId").ifBlank { value.optString("note_id") }
                    .ifBlank { value.optString("id") }
                val id = rawId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,}")) }
                    ?: value.optString("displayTitle").takeIf(String::isNotBlank)?.let { "profile-${result.size + 1}" }
                if (id != null) {
                    val title = value.optString("displayTitle").ifBlank { value.optString("title") }
                        .ifBlank { value.optString("desc") }
                    val cover = findCoverUrl(value)
                    val token = value.optString("xsecToken").ifBlank { value.optString("xsec_token") }
                    val source = if (rawId.matches(Regex("[A-Za-z0-9_-]{8,}"))) buildString {
                        append("https://www.xiaohongshu.com/explore/").append(id)
                        if (token.isNotBlank()) {
                            append("?xsec_token=")
                            append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
                            append("&xsec_source=pc_user")
                        }
                    } else profileUrl
                    result.putIfAbsent(id, DouyinCreatorWork(id, title, cover, source, 1, false))
                }
                value.keys().forEach { key -> collectNotes(value.opt(key), result, profileUrl) }
            }
            is JSONArray -> for (index in 0 until value.length()) collectNotes(value.opt(index), result, profileUrl)
        }
    }

    private fun findNamedValue(value: Any?, name: String): Any? = when (value) {
        is JSONObject -> if (value.has(name)) value.opt(name) else value.keys().asSequence()
            .mapNotNull { findNamedValue(value.opt(it), name) }.firstOrNull()
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { findNamedValue(value.opt(it), name) }.firstOrNull()
        else -> null
    }

    private fun collectFeedCards(
        value: Any?,
        result: LinkedHashMap<String, DouyinCreatorWork>,
        profileUrl: String,
    ) {
        when (value) {
            is JSONObject -> {
                val card = value.optJSONObject("noteCard")
                if (card != null && card.optString("displayTitle").isNotBlank()) {
                    val rawId = card.optString("noteId").ifBlank { value.optString("noteId") }
                        .ifBlank { value.optString("id") }
                    val id = rawId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,}")) }
                        ?: "profile-${result.size + 1}"
                    val token = card.optString("xsecToken").ifBlank { value.optString("xsecToken") }
                    val source = if (rawId.matches(Regex("[A-Za-z0-9_-]{8,}"))) buildString {
                        append("https://www.xiaohongshu.com/explore/").append(id)
                        if (token.isNotBlank()) {
                            append("?xsec_token=")
                            append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
                            append("&xsec_source=pc_user")
                        }
                    } else profileUrl
                    result.putIfAbsent(
                        id,
                        DouyinCreatorWork(
                            id = id,
                            title = card.optString("displayTitle"),
                            coverUrl = findCoverUrl(card),
                            sourceUrl = source,
                            mediaCount = 1,
                            isGallery = card.optString("type").equals("video", ignoreCase = true),
                        ),
                    )
                }
                value.keys().forEach { key -> collectFeedCards(value.opt(key), result, profileUrl) }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectFeedCards(value.opt(index), result, profileUrl)
            }
        }
    }

    private fun findCoverUrl(value: JSONObject): String {
        val cover = value.optJSONObject("cover") ?: value.optJSONObject("image") ?: JSONObject()
        val infoList = cover.optJSONArray("infoList")
        val infoUrl = infoList?.let { list ->
            val preferred = (0 until list.length())
                .mapNotNull { list.optJSONObject(it) }
                .firstOrNull { item ->
                    item.optString("imageScene").uppercase() in setOf("WB_PRV", "H5_DTL", "CRP")
                }
            preferred?.optString("url")
        }.orEmpty()
        return sequenceOf(
            infoUrl,
            cover.optString("urlDefault"),
            cover.optString("urlPre"),
            cover.optString("url"),
            cover.optString("url_default"),
            cover.optString("url_pre"),
        )
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    private fun collectLegacyLinks(html: String, result: LinkedHashMap<String, DouyinCreatorWork>) {
        Regex("/(?:explore|discovery/item)/([A-Za-z0-9_-]{8,})", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { id ->
                result.putIfAbsent(id, DouyinCreatorWork(id, "", "", "https://www.xiaohongshu.com/explore/$id", 1, false))
            }
    }

    /** Some XHS responses contain the rendered cards but omit/relocate the state.notes node. */
    private fun collectRawProfileCards(
        html: String,
        result: LinkedHashMap<String, DouyinCreatorWork>,
        profileUrl: String,
    ) {
        val titleRegex = Regex("\\\"displayTitle\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        titleRegex.findAll(html).forEachIndexed { index, match ->
            val title = match.groupValues[1]
                .replace("\\\\\\\"", "\\\"")
                .replace("\\\\n", " ")
                .trim()
            if (title.isBlank()) return@forEachIndexed
            val window = html.substring(match.range.last, minOf(html.length, match.range.last + 3500))
            val cover = Regex("\\\"urlDefault\\\"\\s*:\\s*\\\"([^\\\"]+)").find(window)
                ?.groupValues?.get(1).orEmpty()
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            val id = "profile-${index + 1}"
            result.putIfAbsent(id, DouyinCreatorWork(id, title, cover, profileUrl, 1, false))
        }
    }

    private fun Any?.unwrapVueValue(): Any? = when (this) {
        is JSONObject -> when {
            has("value") -> opt("value")
            has("_value") -> opt("_value")
            else -> this
        }
        else -> this
    }
}

internal class XiaohongshuCreatorRepository(private val context: Context) {
    fun loadPage(creator: CreatorProfile): DouyinCreatorPage {
        val profileUrl = creator.profileUrl.ifBlank {
            buildString {
                append("https://www.xiaohongshu.com/user/profile/").append(creator.id)
                if (creator.accessToken.isNotBlank()) {
                    append("?xsec_token=")
                    append(URLEncoder.encode(creator.accessToken, StandardCharsets.UTF_8.name()))
                    append("&xsec_source=pc_user")
                }
            }
        }
        if (android.os.Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            android.os.Build.MODEL.contains("sdk", ignoreCase = true)
        ) {
            loadFromLocalBridge(profileUrl, creator)?.takeIf { it.works.size > 1 }?.let { return it }
        }
        val connection = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 12_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36")
            setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            // The profile xsec_token is sufficient here. Reusing the stale login-shell
            // cookie makes XHS return the 10 KB empty shell instead of SSR notes.
        }
        try {
            if (connection.responseCode !in 200..299) throw XiaohongshuException("HTTP ${connection.responseCode}")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val initial = XiaohongshuCreatorPageNormalizer.normalize(html, creator)
            if (initial.works.isNotEmpty()) return initial
            val redirectCookies = connection.headerFields["Set-Cookie"].orEmpty()
                .map { it.substringBefore(';') }
                .filter { it.isNotBlank() }
                .joinToString("; ")
            if (redirectCookies.isBlank()) return initial
            connection.disconnect()
            val retry = (URL(profileUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 12_000; readTimeout = 20_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36")
                setRequestProperty("Referer", "https://www.xiaohongshu.com/")
                setRequestProperty("Cookie", redirectCookies)
            }
            return try {
                if (retry.responseCode !in 200..299) initial
                else XiaohongshuCreatorPageNormalizer.normalize(
                    retry.inputStream.bufferedReader().use { it.readText() }, creator,
                ).takeIf { it.works.isNotEmpty() } ?: initial
            } finally {
                retry.disconnect()
            }
        } finally { connection.disconnect() }
    }

    private fun loadFromLocalBridge(profileUrl: String, creator: CreatorProfile): DouyinCreatorPage? = runCatching {
        val bridgeUrl = "http://10.0.2.2:18765/profile?url=" +
            URLEncoder.encode(profileUrl, StandardCharsets.UTF_8.name())
        val connection = (URL(bridgeUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            XiaohongshuCreatorPageNormalizer.normalize(
                connection.inputStream.bufferedReader().use { it.readText() },
                creator,
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
