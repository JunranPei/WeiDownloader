package cn.weibo.filedownloader

import android.content.Context
import org.json.JSONObject

/**
 * Orchestrates Weibo link resolution, provider fallback, and media
 * normalization. Platform-specific details live in replaceable modules.
 */
class WeiboClient(
    private val cookie: String,
    private val context: Context,
    includeLiveCover: Boolean = false,
) {
    private val http = WeiboHttpClient(cookie, context)
    private val linkResolver = WeiboLinkResolver(http, context)
    private val normalizer = WeiboWorkNormalizer(cookie, includeLiveCover)
    private val statusProviders: List<WeiboStatusDetailProvider> = listOf(
        WeiboMobileStatusDetailProvider(http),
        WeiboDesktopStatusDetailProvider(http, context),
    )
    private val videoProviders: List<WeiboVideoDetailProvider> = listOf(
        WeiboComponentVideoDetailProvider.h5(http, cookie, context),
        WeiboComponentVideoDetailProvider.desktop(http, cookie, context),
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
        val signedIn = cookie.contains("SUB=")
        val defaultAuthor = context.getString(R.string.default_author)
        var lastFailure: Exception? = null
        for (provider in statusProviders) {
            val data = try {
                provider.load(id)
            } catch (error: Exception) {
                lastFailure = error
                null
            } ?: continue
            normalizer.normalizeStatus(data, id, quality, defaultAuthor)?.let { return it.toPost(sourceUrl) }

            // Some status responses retain only the video object id while their
            // signed media URL fields are temporarily empty. Resolve that object
            // through the independent video-component providers before moving on.
            statusVideoObjectId(data)?.let { objectId ->
                try {
                    val playInfo = loadVideoFromProviders(objectId)
                    normalizer.normalizeVideoComponent(playInfo, objectId, quality, defaultAuthor)
                        ?.let { return it.toPost(sourceUrl) }
                } catch (error: Exception) {
                    lastFailure = error
                }
            }
        }
        if (!signedIn) {
            throw WeiboLoginRequiredException(context.getString(R.string.client_login_required))
        }
        throw lastFailure ?: WeiboException(context.getString(R.string.no_media))
    }

    private fun loadVideoObject(objectId: String, sourceUrl: String, quality: QualityPreference): WeiboPost {
        val playInfo = loadVideoFromProviders(objectId)
        val work = normalizer.normalizeVideoComponent(
            playInfo = playInfo,
            objectId = objectId,
            quality = quality,
            defaultAuthor = context.getString(R.string.default_author),
        ) ?: throw WeiboException(context.getString(R.string.no_media))
        return work.toPost(sourceUrl)
    }

    private fun loadVideoFromProviders(objectId: String): JSONObject {
        var lastFailure: Exception? = null
        for (provider in videoProviders) {
            val detail = try {
                provider.load(objectId)
            } catch (error: Exception) {
                lastFailure = error
                null
            }
            if (detail != null) return detail
        }
        throw lastFailure ?: WeiboException(context.getString(R.string.no_media))
    }

    internal fun parseMedia(
        data: JSONObject,
        quality: QualityPreference,
        postId: String = data.optString("id"),
    ): List<MediaItem> = normalizer.parseMedia(data, quality, postId)

    private fun statusVideoObjectId(data: JSONObject): String? = sequenceOf(
        data.optJSONObject("page_info")?.optString("object_id"),
        data.optString("object_id"),
        data.optString("fid"),
    ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull { it.matches(Regex("[0-9]+:[A-Za-z0-9]+")) }

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
