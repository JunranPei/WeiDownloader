package cn.weibo.filedownloader

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal data class WeiboMediaOrder(val keys: List<String>, val priority: Int)
internal data class WeiboMediaReference(val key: String, val type: MediaType, val objectId: String = "", val link: String = "")

/** Source-declared identities/order survive even when an attachment has no playable URL yet. */
internal data class WeiboMediaInventory(
    val references: List<WeiboMediaReference> = emptyList(),
    val orders: List<WeiboMediaOrder> = emptyList(),
    val minimumCount: Int = 0,
    val aliases: List<Pair<String, String>> = emptyList(),
) {
    fun keys(item: MediaItem): Set<String> {
        val keys = WeiboMediaIdentity.keys(item).toMutableSet()
        do {
            val previousSize = keys.size
            aliases.forEach { (a, b) -> if (a in keys || b in keys) { keys += a; keys += b } }
        } while (keys.size != previousSize)
        return keys
    }

    fun missing(media: List<MediaItem>): List<WeiboMediaReference> = references.filter { reference ->
        media.none { item -> reference.key in keys(item) &&
            (reference.type == item.type || reference.type == MediaType.IMAGE && item.type == MediaType.LIVE_PHOTO) }
    }.distinctBy { it.key }

    companion object {
        fun source(status: JSONObject): JSONObject {
            if (listOf("pic_ids", "pics", "mix_media_ids").any { (status.optJSONArray(it)?.length() ?: 0) > 0 } ||
                (status.optJSONObject("pic_infos")?.length() ?: 0) > 0 ||
                (status.optJSONObject("mix_media_info")?.optJSONArray("items")?.length() ?: 0) > 0) return status
            val forwarded = status.optJSONObject("retweeted_status")?.let(::source)
            return forwarded?.takeIf { read(it).references.isNotEmpty() ||
                it.optJSONObject("page_info")?.optJSONObject("media_info") != null || it.optJSONObject("media_info") != null } ?: status
        }

        fun read(status: JSONObject): WeiboMediaInventory {
            val references = mutableListOf<WeiboMediaReference>()
            val orders = mutableListOf<WeiboMediaOrder>()
            val aliases = linkedMapOf<String, String>()
            fun video(data: JSONObject, hint: String, fallback: String): WeiboMediaReference {
                val reference = videoReference(data, hint, fallback)
                val info = data.optJSONObject("media_info") ?: data.optJSONObject("media") ?: data
                // Preserve preview URL identity even when playback_list selects a different resolution.
                for (container in listOf(data, info)) for (field in listOf("videoSrc", "stream_url", "stream_url_hd", "mp4_sd_url", "mp4_hd_url")) {
                    container.optString(field).takeIf { it.startsWith("http") || it.startsWith("//") }?.let {
                        aliases[WeiboMediaIdentity.resourceKey(it)] = reference.key
                    }
                }
                if (reference.link.isNotBlank()) aliases["link:${reference.link.removePrefix("http://").removePrefix("https://")}"] = reference.key
                return reference
            }
            fun order(items: List<WeiboMediaReference>, priority: Int) {
                references += items
                if (items.isNotEmpty()) orders += WeiboMediaOrder(items.map { it.key }, priority)
            }
            val infos = status.optJSONObject("pic_infos")
            val liveHints = liveVideoHints(status)
            order(strings(status.optJSONArray("pic_ids")).mapIndexedNotNull { index, id ->
                if (WeiboMediaIdentity.pictureIdPattern.matches(id) || infos?.has(id) == true)
                    WeiboMediaReference("picture:$id", if (liveHints.containsKey(index)) MediaType.LIVE_PHOTO else MediaType.IMAGE)
                else null
            }, 1)
            infos?.keys()?.forEach { id ->
                references += pictureReference(infos.optJSONObject(id) ?: JSONObject(), id)
            }
            val mixedIds = strings(status.optJSONArray("mix_media_ids"))
            val pics = status.optJSONArray("pics")
            order((0 until (pics?.length() ?: 0)).mapNotNull { index ->
                pics?.optJSONObject(index)?.let { picture ->
                    if (picture.optString("type").equals("video", true)) video(picture, mixedIds.getOrNull(index).orEmpty(), "pics:$index")
                    else pictureReference(picture, picture.optString("pid").ifBlank { picture.optString("pic_id") })
                }
            }, 2)
            // This list can extend beyond pics; keep its unresolved video/link slots too.
            order(mixedIds.mapIndexedNotNull { index, id ->
                if (id.isBlank() || id == "null") return@mapIndexedNotNull null
                if (WeiboMediaIdentity.pictureIdPattern.matches(id)) WeiboMediaReference("picture:$id", MediaType.IMAGE)
                else videoReference(JSONObject(), id, "mixed:$index")
            }, 2)
            val mixed = status.optJSONObject("mix_media_info")?.optJSONArray("items")
            order((0 until (mixed?.length() ?: 0)).mapNotNull { index ->
                mixed?.optJSONObject(index)?.let { item ->
                    val data = item.optJSONObject("data") ?: item
                    if (item.optString("type", data.optString("type")).equals("video", true) || data.optString("object_type").equals("video", true)) {
                        video(data, item.optString("id"), "mixed:$index")
                    } else pictureReference(data.optJSONObject("pic_info") ?: data,
                        item.optString("id").ifBlank { data.optString("pic_id") })
                }
            }, 3)
            status.optJSONObject("page_info")?.let { page ->
                if (isVideo(page)) {
                    references += video(page, "", "page")
                }
            }
            if (isVideo(status)) references += video(status, "", "status")
            return WeiboMediaInventory(references.distinct(), orders, status.optInt("pic_num").coerceAtLeast(0), aliases.toList())
        }

        /** Returns live-video hints keyed by their picture index. */
        fun liveVideoHints(status: JSONObject): Map<Int, String> {
            val result = linkedMapOf<Int, String>()
            val indexed = status.optString("pic_video").trim()
            if (indexed.isNotBlank()) {
                indexed.split(',').forEach { entry ->
                    val separator = entry.indexOf(':')
                    if (separator <= 0) return@forEach
                    val index = entry.substring(0, separator).trim().toIntOrNull() ?: return@forEach
                    val value = entry.substring(separator + 1).trim()
                    if (value.isNotBlank()) result[index] = value
                }
            }
            val livePhotos = when (val value = status.opt("live_photo")) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotBlank) }
                is String -> value.trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
                else -> emptyList()
            }
            if (livePhotos.isNotEmpty() && result.isEmpty()) {
                var next = 0
                livePhotos.forEach { value ->
                    while (result.containsKey(next)) next++
                    result.putIfAbsent(next++, value)
                }
            }
            return result
        }

        fun pictureReference(data: JSONObject, id: String): WeiboMediaReference {
            val key = id.takeIf { it.isNotBlank() && it != "null" }?.let { "picture:$it" }
                ?: listOf("large", "largest", "url", "bmiddle").asSequence()
                    .mapNotNull { url(data.opt(it))?.takeIf(String::isNotBlank)?.let { address ->
                        WeiboMediaIdentity.pictureKey(address) ?: WeiboMediaIdentity.resourceKey(address)
                    } }.firstOrNull()
                ?: "unresolved-picture:${data.toString().hashCode()}"
            return WeiboMediaReference(key, if (data.optString("type").equals("livephoto", true)) MediaType.LIVE_PHOTO else MediaType.IMAGE)
        }

        fun isVideo(data: JSONObject): Boolean = data.optString("type").equals("video", true) ||
            data.optString("object_type").equals("video", true) || videoReference(data, "", "").objectId.isNotBlank()

        fun videoReference(data: JSONObject, hint: String, fallback: String): WeiboMediaReference {
            val media = data.optJSONObject("media_info") ?: data.optJSONObject("media") ?: data
            val objectId = sequenceOf(data.optString("object_id"), data.optString("fid"), media.optString("object_id"), media.optString("fid"), hint)
                .firstOrNull { it.matches(WeiboMediaIdentity.objectIdPattern) }.orEmpty()
            val link = hint.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
            val address = sequenceOf(data.optString("videoSrc"), media.optString("stream_url"), media.optString("stream_url_hd"),
                media.optJSONArray("playback_list")?.optJSONObject(0)?.optJSONObject("play_info")?.optString("url").orEmpty())
                .firstOrNull { it.startsWith("http") }.orEmpty()
            val key = when {
                objectId.isNotBlank() -> "video:$objectId"
                link.isNotBlank() -> "link:${link.removePrefix("http://").removePrefix("https://")}"
                address.isNotBlank() -> WeiboMediaIdentity.resourceKey(address)
                else -> "unresolved-video:$fallback"
            }
            return WeiboMediaReference(key, MediaType.VIDEO, objectId, link)
        }

        private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0))
            .map { array!!.optString(it).takeUnless { value -> value == "null" }.orEmpty() }
        private fun url(value: Any?): String? = when (value) {
            is String -> value
            is JSONObject -> value.optString("url").takeIf(String::isNotBlank)
            else -> null
        }
    }
}

internal object WeiboMediaIdentity {
    val pictureIdPattern = Regex("[A-Za-z0-9]{20,64}")
    val objectIdPattern = Regex("[0-9]+:[A-Za-z0-9]+")
    fun pictureKey(address: String): String? = runCatching { URI(address) }.getOrNull()
        ?.takeIf { it.host.orEmpty().lowercase().endsWith(".sinaimg.cn") }
        ?.path?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf(String::isNotBlank)?.let { "picture:$it" }
    fun resourceKey(address: String): String = runCatching {
        val uri = URI(address)
        if (uri.path.orEmpty().endsWith("/media/play")) "resource:${uri.host}${uri.path}?${uri.rawQuery}"
        else "resource:${uri.host}${uri.path}"
    }.getOrDefault("resource:$address")
    fun keys(item: MediaItem): Set<String> = linkedSetOf<String>().apply {
        item.sourceId.takeIf(String::isNotBlank)?.let(::add)
        item.videoObjectId.takeIf(String::isNotBlank)?.let { add("video:$it") }
        if (item.type == MediaType.IMAGE || item.type == MediaType.LIVE_PHOTO) {
            listOf(item.url, item.thumbnailUrl, item.coverUrl).mapNotNull(::pictureKey).forEach(::add)
        }
        (listOf(item.url) + item.variants.map { it.url }).filter(String::isNotBlank).map(::resourceKey).forEach(::add)
    }
}

/** Unions attachments by identity, then applies source ordering constraints, never list size/ordinal matching. */
internal object WeiboInventoryMerger {
    fun merge(first: WeiboWorkData, second: WeiboWorkData): WeiboWorkData {
        check(first.id == second.id) { "Conflicting Weibo status identities" }
        return reconcile(first.copy(media = first.media + second.media, inventory = WeiboMediaInventory(
            first.inventory.references + second.inventory.references,
            first.inventory.orders + second.inventory.orders,
            maxOf(first.inventory.minimumCount, second.inventory.minimumCount),
            first.inventory.aliases + second.inventory.aliases,
        )))
    }

    fun reconcile(work: WeiboWorkData): WeiboWorkData {
        data class Group(var item: MediaItem, val keys: MutableSet<String>)
        val groups = mutableListOf<Group>()
        work.media.forEach { item ->
            val keys = work.inventory.keys(item)
            val group = groups.singleOrNull { group ->
                val sameKind = group.item.type == item.type || setOf(group.item.type, item.type) == setOf(MediaType.IMAGE, MediaType.LIVE_PHOTO)
                val conflictingVideoIds = group.item.videoObjectId.isNotBlank() && item.videoObjectId.isNotBlank() && group.item.videoObjectId != item.videoObjectId
                sameKind && !conflictingVideoIds && group.keys.any { it in keys }
            }
            if (group == null) groups += Group(item, keys.toMutableSet())
            else { group.item = mergeItem(group.item, item); group.keys += keys }
        }
        val aliases = groups.flatMap { group -> group.keys.map { it to WeiboMediaIdentity.keys(group.item).first() } }.toMap()
        val byKey = groups.associateBy { WeiboMediaIdentity.keys(it.item).first() }
        val orders = work.inventory.orders.sortedWith(compareByDescending<WeiboMediaOrder> { it.priority }.thenByDescending { it.keys.size })
        val nodes = linkedSetOf<String>()
        orders.forEach { hint -> hint.keys.forEach { nodes += aliases[it] ?: it } }
        nodes += byKey.keys
        val edges = nodes.associateWith { linkedSetOf<String>() }
        fun reaches(from: String, to: String, visited: MutableSet<String> = hashSetOf()): Boolean =
            from == to || visited.add(from) && edges[from].orEmpty().any { reaches(it, to, visited) }
        orders.forEach { hint ->
            hint.keys.map { aliases[it] ?: it }.distinct().zipWithNext().forEach { (a, b) ->
                if (!reaches(b, a)) edges.getValue(a) += b
            }
        }
        val pending = nodes.toMutableSet()
        val sorted = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val next = pending.first { candidate -> pending.none { candidate in edges[it].orEmpty() } }
            sorted += next
            pending -= next
        }
        val media = sorted.mapIndexedNotNull { index, key -> byKey[key]?.item?.copy(ordinal = index + 1) }
        return work.copy(media = media, inventory = work.inventory.copy(aliases = (work.inventory.aliases + aliases.toList()).distinct()))
    }

    fun mergeItem(first: MediaItem, second: MediaItem): MediaItem {
        if (first.type == MediaType.VIDEO && second.type == MediaType.VIDEO) return WeiboVideoWorkResolver.mergeVideo(first, second)
        val preferred = when {
            first.type == MediaType.LIVE_PHOTO -> first
            second.type == MediaType.LIVE_PHOTO -> second
            second.variants.size > first.variants.size -> second
            else -> first
        }
        val other = if (preferred === first) second else first
        val sameType = preferred.type == other.type
        return preferred.copy(postId = first.postId, ordinal = first.ordinal,
            sourceId = preferred.sourceId.ifBlank { other.sourceId },
            thumbnailUrl = preferred.thumbnailUrl.ifBlank { other.thumbnailUrl },
            variants = if (sameType) (preferred.variants + other.variants).distinctBy { it.url }.distinctBy { it.key } else preferred.variants,
            fallbackUrls = if (sameType) (preferred.fallbackUrls + other.fallbackUrls + other.url).filter { it != preferred.url }.distinct() else preferred.fallbackUrls)
    }
}

internal class WeiboIncompleteMediaException : IllegalStateException("Weibo media inventory is incomplete")
