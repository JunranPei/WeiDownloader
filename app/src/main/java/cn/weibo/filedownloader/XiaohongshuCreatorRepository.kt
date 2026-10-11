package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private fun isXiaohongshuNoteId(value: String): Boolean =
    value.matches(Regex("^[0-9a-fA-F]{24}$")) ||
        value.matches(Regex("^[0-9a-fA-F]{32}$"))

private fun isUsableProfileNote(id: String, token: String): Boolean =
    isXiaohongshuNoteId(id) && (id.length == 24 || token.isNotBlank())

private fun embeddedNoteId(value: String, marker: String): String? {
    val markerIndex = value.lastIndexOf(marker)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length
    return Regex("^(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{24})(?![0-9a-fA-F])")
        .find(value.substring(start))?.value?.takeIf(::isXiaohongshuNoteId)
}

private fun recoverEncodedNoteId(note: JSONObject): String? {
    val cover = note.optJSONObject("cover") ?: return null
    val user = note.optJSONObject("user")
    val urlPre = cover.optString("urlPre")
    val urlDefault = cover.optString("urlDefault")
    val recovered = when {
        urlPre.endsWith("##c=vp4") -> embeddedNoteId(cover.optString("fileId"), "%%")
        urlDefault.endsWith("##c=vp6") -> embeddedNoteId(user?.optString("avatar").orEmpty(), "#%")
        urlDefault.endsWith("##c=vp1") -> embeddedNoteId(cover.optString("traceId"), "#")
        else -> null
    }
    return recovered
}

private fun findNoteId(note: JSONObject, parent: JSONObject? = null): String? {
    val candidates = sequenceOf(
        note.optString("noteId"), note.optString("note_id"), note.optString("id"),
        parent?.optString("noteId").orEmpty(), parent?.optString("note_id").orEmpty(),
        parent?.optString("id").orEmpty(),
    )
    return candidates.firstOrNull(::isXiaohongshuNoteId)
        ?: recoverEncodedNoteId(note)
        ?: parent?.let(::recoverEncodedNoteId)
}

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
        if (works.isEmpty()) collectLegacyLinks(html, works)
        return DouyinCreatorPage(creator, works.values.toList(), 0L, false)
    }

    private fun collectNotes(value: Any?, result: LinkedHashMap<String, DouyinCreatorWork>, profileUrl: String) {
        when (value) {
            is JSONObject -> {
                val id = findNoteId(value)
                if (id != null) {
                    val title = value.optString("displayTitle").ifBlank { value.optString("title") }
                        .ifBlank { value.optString("desc") }
                    val cover = findCoverUrl(value)
                    val token = value.optString("xsecToken")
                        .ifBlank { value.optString("xsec_token") }

                    if (isUsableProfileNote(id, token)) {
                        val source = buildWorkUrl(id, token, profileUrl)
                        result.putIfAbsent(id, DouyinCreatorWork(id, title, cover, source, 1, false))
                    }
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
                    val id = findNoteId(card, value)
                    if (id != null) {
                        val token = card.optString("xsecToken")
                            .ifBlank { value.optString("xsecToken") }

                        if (isUsableProfileNote(id, token)) {
                            val source = buildWorkUrl(id, token, profileUrl)
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
                    }
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
            .replace(Regex("##c=vp[146]$"), "")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    private fun collectLegacyLinks(html: String, result: LinkedHashMap<String, DouyinCreatorWork>) {
        Regex("/(?:explore|discovery/item)/((?:[0-9a-fA-F]{24}|[0-9a-fA-F]{32}))(?:[?\"'\\s]|$)", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { id ->
                result.putIfAbsent(id, DouyinCreatorWork(id, "", "", "https://www.xiaohongshu.com/explore/$id", 1, false))
            }
    }

    private fun buildWorkUrl(id: String, token: String, profileUrl: String): String = buildString {
        append("https://www.xiaohongshu.com/explore/").append(id)
        var hasQuery = false
        if (token.isNotBlank()) {
            append("?xsec_token=")
            append(URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
            append("&xsec_source=pc_user")
            hasQuery = true
        }
        if (profileUrl.contains("xsec_token=", ignoreCase = true)) {
            append(if (hasQuery) '&' else '?')
            append("xhs_profile=")
            append(URLEncoder.encode(profileUrl, StandardCharsets.UTF_8.name()))
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

}
