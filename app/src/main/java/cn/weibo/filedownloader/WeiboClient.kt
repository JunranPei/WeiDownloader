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
        if (!cookie.contains("SUB=")) {
            throw WeiboLoginRequiredException(context.getString(R.string.client_login_required))
        }
        val data = loadStatusFromProviders(id)
        val work = normalizer.normalizeStatus(
            data = data,
            fallbackId = id,
            quality = quality,
            defaultAuthor = context.getString(R.string.default_author),
        ) ?: throw WeiboException(context.getString(R.string.no_media))
        return work.toPost(sourceUrl)
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

    private fun loadStatusFromProviders(id: String): JSONObject {
        var lastFailure: Exception? = null
        for (provider in statusProviders) {
            val detail = try {
                provider.load(id)
            } catch (error: Exception) {
                lastFailure = error
                null
            }
            if (detail != null) return detail
        }
        throw lastFailure ?: WeiboException(context.getString(R.string.post_not_returned))
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
