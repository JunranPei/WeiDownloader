package cn.weibo.filedownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboWorkNormalizerTest {
    @Test
    fun audioAttachmentKeepsItsTypeAndFollowsThePostsImage() {
        val status = JSONObject("""{
            "id":"audio-post",
            "pic_ids":["photo"],
            "pic_infos":{"photo":{"large":{"url":"https://wx1.sinaimg.cn/large/photo.jpg"}}},
            "page_info":{"object_type":"audio","media_info":{"audio_url":"https://media.example/recording.mp3"}}
        }""")
        val media = normalizer().parseMedia(status, QualityPreference.HIGHEST, "audio-post")
        assertEquals(listOf(MediaType.IMAGE, MediaType.AUDIO), media.map { it.type })
        assertEquals(listOf(1, 2), media.map { it.ordinal })
        assertEquals("audio_audio-post_02.mp3", media.last().suggestedFileName())
        assertEquals("https://media.example/recording.mp3", media.last().url)
        assertTrue(media.all { it.postId == "audio-post" })
    }

    @Test
    fun findsForwardedOriginalImageAndKeepsRootPostId() {
        val status = JSONObject(
            """
            {
              "id": "root-post",
              "retweeted_status": {
                "pic_ids": ["pic-1"],
                "pic_infos": {
                  "pic-1": {
                    "thumbnail": {
                      "url": "https://wx1.sinaimg.cn/thumbnail/example.jpg",
                      "width": 180,
                      "height": 180
                    },
                    "mw2000": {
                      "url": "https://wx1.sinaimg.cn/mw2000/example.jpg",
                      "width": 2000,
                      "height": 1400
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val media = normalizer().parseMedia(status, QualityPreference.HIGHEST, "root-post").single()

        assertEquals(MediaType.IMAGE, media.type)
        assertEquals("root-post", media.postId)
        assertEquals("https://wx1.sinaimg.cn/large/example.jpg", media.url)
        assertEquals("original", media.selectedVariantKey)
    }

    @Test
    fun pairsLivePhotoVideoWithOptionalOriginalCover() {
        val status = JSONObject(
            """
            {
              "id": "live-post",
              "pic_ids": ["pic-1"],
              "pic_infos": {
                "pic-1": {
                  "type": "livephoto",
                  "fid": "live-file.mov",
                  "large": {
                    "url": "https://wx1.sinaimg.cn/large/live-cover.jpg",
                    "width": 2048,
                    "height": 1536
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val media = normalizer(includeLiveCover = true)
            .parseMedia(status, QualityPreference.HIGHEST, "live-post")
            .single()

        assertEquals(MediaType.LIVE_PHOTO, media.type)
        assertTrue(media.url.contains("live-file.mov"))
        assertEquals("https://wx1.sinaimg.cn/large/live-cover.jpg", media.coverUrl)
    }

    @Test
    fun readsTopLevelPicVideoHintsUsedByMobileLivePhotoResponses() {
        val status = JSONObject(
            """
            {
              "id": "live-post",
              "pic_ids": ["pic-1", "pic-2"],
              "pic_video": "1:second-live-fid",
              "pic_infos": {
                "pic-1": {"large": {"url": "https://wx1.sinaimg.cn/large/one.jpg"}},
                "pic-2": {"large": {"url": "https://wx1.sinaimg.cn/large/two.jpg"}}
              }
            }
            """.trimIndent(),
        )

        val media = normalizer().parseMedia(status, QualityPreference.HIGHEST, "live-post")

        assertEquals(listOf(MediaType.IMAGE, MediaType.LIVE_PHOTO), media.map { it.type })
        assertTrue(media[1].url.contains("second-live-fid.mov"))
    }

    @Test
    fun ranksPlaybackListByActualVideoSpecification() {
        val status = JSONObject(
            """
            {
              "id": "video-post",
              "page_info": {
                "media_info": {
                  "playback_list": [
                    {
                      "play_info": {
                        "url": "https://f.video.weibocdn.com/low.mp4",
                        "width": 640,
                        "height": 360,
                        "bitrate": 500000
                      },
                      "meta": {"quality_desc": "360p"}
                    },
                    {
                      "play_info": {
                        "url": "https://f.video.weibocdn.com/high.mp4",
                        "width": 1920,
                        "height": 1080,
                        "bitrate": 2500000
                      },
                      "meta": {"quality_desc": "1080p"}
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val media = normalizer().parseMedia(status, QualityPreference.HIGHEST, "video-post").single()

        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://f.video.weibocdn.com/high.mp4", media.url)
        assertEquals("1080p", media.selectedVariantKey)
        assertEquals(2, media.variants.size)
    }

    @Test
    fun findsVideoPosterInsideNestedMediaInfo() {
        val playInfo = JSONObject(
            """
            {
              "nickname": "author",
              "urls": {
                "高清 1080P": "https://f.video.weibocdn.com/video.mp4"
              },
              "media_info": {
                "poster_url": "https://wx1.sinaimg.cn/large/video-poster.jpg"
              }
            }
            """.trimIndent(),
        )

        val media = normalizer()
            .normalizeVideoComponent(playInfo, "1034:123456789", QualityPreference.HIGHEST, "unknown")
            ?.media
            ?.single()

        assertEquals("https://wx1.sinaimg.cn/large/video-poster.jpg", media?.thumbnailUrl)
    }

    @Test
    fun readsNumericMidVideoFromSiblingUrlsWhenMediaInfoIsEmpty() {
        val status = JSONObject(
            """
            {
              "id": "5341164280481000",
              "page_info": {
                "type": "video",
                "object_id": "1034:5341163884838960",
                "page_pic": {
                  "url": "https://wx3.sinaimg.cn/orj480/video-cover.jpg",
                  "width": 480,
                  "height": 945
                },
                "media_info": {
                  "duration": 66.688
                },
                "urls": {
                  "mp4_720p_mp4": "https://f.video.weibocdn.com/source.mp4?template=720x1416.24.0"
                }
              }
            }
            """.trimIndent(),
        )

        val media = normalizer()
            .parseMedia(status, QualityPreference.HIGHEST, "5341164280481000")
            .single()

        assertEquals(MediaType.VIDEO, media.type)
        assertEquals("https://f.video.weibocdn.com/source.mp4?template=720x1416.24.0", media.url)
        assertEquals(720, media.variants.first().width)
        assertEquals(1416, media.variants.first().height)
        assertEquals("https://wx3.sinaimg.cn/orj480/video-cover.jpg", media.thumbnailUrl)
    }

    private fun normalizer(includeLiveCover: Boolean = false) =
        WeiboWorkNormalizer(
            includeLiveCover = includeLiveCover,
            metadataProbe = object : WeiboMediaMetadataProbe {
                override fun imageDimensions(url: String): Pair<Int, Int>? = null
                override fun mediaMetadata(url: String): WeiboMediaProbeResult? = null
            },
        )
}
