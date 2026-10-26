package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject

/**
 * Orchestrates Weibo link resolution, provider fallback, and media
 * normalization. Platform-specific details live in replaceable modules.
 */
class WeiboClient(
    private val context: Context,
    includeLiveCover: Boolean = false,
) {
    private val http = WeiboHttpClient(context)
    private val linkResolver = WeiboLinkResolver(http, context)
    private val normalizer = WeiboWorkNormalizer(includeLiveCover)
    private val statusProviders: List<WeiboStatusDetailProvider> = listOf(
        WeiboMobileStatusDetailProvider(http, context),
        WeiboDesktopStatusDetailProvider(http, context),
    )
    private val videoProviders: List<WeiboVideoDetailProvider> = listOf(
        WeiboComponentVideoDetailProvider.h5(http, context),
        WeiboComponentVideoDetailProvider.desktop(http, context),
    )

    fun loadPost(sharedUrl: String, quality: QualityPreference = QualityPreference.HIGHEST): WeiboPost {
        val target = linkResolver.resolve(sharedUrl)
        return when (val link = target.target) {
            is WeiboLinkTarget.Status -> loadStatusPost(link.id, target.sourceUrl, quality)
            is WeiboLinkTarget.VideoObject -> runCatching {
                loadVideoObject(link.objectId, target.sourceUrl, quality)
            }.getOrElse { videoError ->
                link.fallbackStatusId?.let { loadStatusPost(it, target.sourceUrl, quality) } ?: throw videoError
            }
        }
    }

    private fun loadStatusPost(id: String, sourceUrl: String, quality: QualityPreference): WeiboPost {
        val defaultAuthor = context.getString(R.string.default_author)
        val components = HashMap<String, WeiboWorkData?>()
        fun component(objectId: String): WeiboWorkData? = if (components.containsKey(objectId)) components[objectId]
            else runCatching { loadVideoWork(objectId, quality, resolveStatus = false) }.getOrNull()
                .also { components[objectId] = it }
        val work = try { WeiboStatusWorkResolver.resolve(
            providers = statusProviders.map { provider -> { provider.load(id) } },
            normalize = { data ->
                normalizer.normalizeStatus(data, id, quality, defaultAuthor)
            },
            complete = { status -> WeiboStatusWorkResolver.completeVideos(status) { reference ->
                val objectId = reference.objectId.ifBlank {
                    if (reference.link.isBlank()) ""
                    else (linkResolver.resolve(reference.link).target as? WeiboLinkTarget.VideoObject)?.objectId.orEmpty()
                }
                if (objectId.isBlank()) null else component(objectId)?.media?.singleOrNull {
                    it.type == MediaType.VIDEO && it.videoObjectId == objectId
                }
            } },
        ) } catch (_: WeiboIncompleteMediaException) {
            throw WeiboException(context.getString(R.string.weibo_media_incomplete))
        } ?: throw WeiboException(context.getString(R.string.no_media))
        return WeiboVideoWorkResolver.upgradeVideos(work, ::component).toPost(sourceUrl)
    }

    private fun loadVideoObject(objectId: String, sourceUrl: String, quality: QualityPreference): WeiboPost {
        val work = loadVideoWork(objectId, quality, resolveStatus = true)
            ?: throw WeiboException(context.getString(R.string.no_media))
        return work.toPost(sourceUrl)
    }

    private fun loadVideoWork(objectId: String, quality: QualityPreference, resolveStatus: Boolean): WeiboWorkData? {
        val defaultAuthor = context.getString(R.string.default_author)
        val statuses = HashMap<String, WeiboWorkData?>()
        return WeiboVideoWorkResolver.resolve(
            providers = videoProviders.map { provider -> { provider.load(objectId) } },
            resolveStatus = { mid ->
                if (!resolveStatus) null
                else if (statuses.containsKey(mid)) statuses[mid]
                else loadVideoStatus(mid, objectId, quality, defaultAuthor).also { statuses[mid] = it }
            },
            normalizeComponent = { detail -> normalizer.normalizeVideoComponent(detail, objectId, quality, defaultAuthor) },
        )
    }

    private fun loadVideoStatus(mid: String, objectId: String, quality: QualityPreference, defaultAuthor: String): WeiboWorkData? {
        return WeiboStatusWorkResolver.resolve(
            statusProviders.map { provider -> { provider.load(mid) } },
            { normalizer.normalizeVideoStatus(it, mid, objectId, quality, defaultAuthor) },
        )
    }

    internal fun parseMedia(
        data: JSONObject,
        quality: QualityPreference,
        postId: String = data.optString("id"),
    ): List<MediaItem> = normalizer.parseMedia(data, quality, postId)

    private fun WeiboWorkData.toPost(sourceUrl: String): WeiboPost = WeiboPost(
        id = id,
        text = title,
        author = author,
        sourceUrl = sourceUrl,
        media = media,
    )
}

class WeiboException(message: String) : RuntimeException(message)
class WeiboLoginRequiredException(message: String) : RuntimeException(message)
