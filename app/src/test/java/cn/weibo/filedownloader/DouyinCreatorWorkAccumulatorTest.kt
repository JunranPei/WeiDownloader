package cn.weibo.filedownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinCreatorWorkAccumulatorTest {
    @Test
    fun `keeps works that disappear from a virtualized page`() {
        val accumulator = DouyinCreatorWorkAccumulator()

        val first = accumulator.merge(listOf(work("1"), work("2"), work("3")))
        val second = accumulator.merge(listOf(work("3"), work("4"), work("5")))

        assertEquals(listOf("1", "2", "3"), first.map { it.id })
        assertEquals(listOf("1", "2", "3", "4", "5"), second.map { it.id })
    }

    @Test
    fun `retains richer metadata while merging repeated works`() {
        val accumulator = DouyinCreatorWorkAccumulator()
        accumulator.merge(listOf(work("1", title = "Known title", cover = "cover-a")))

        val merged = accumulator.merge(
            listOf(work("1", title = "", cover = "cover-b", mediaCount = 4, gallery = true)),
        ).single()

        assertEquals("Known title", merged.title)
        assertEquals("cover-b", merged.coverUrl)
        assertEquals(4, merged.mediaCount)
        assertTrue(merged.isGallery)
    }

    @Test
    fun `reset starts a new creator collection`() {
        val accumulator = DouyinCreatorWorkAccumulator()
        accumulator.merge(listOf(work("1"), work("2")))

        accumulator.reset()

        assertEquals(listOf("3"), accumulator.merge(listOf(work("3"))).map { it.id })
    }

    private fun work(
        id: String,
        title: String = "Work $id",
        cover: String = "cover-$id",
        mediaCount: Int = 1,
        gallery: Boolean = false,
    ) = DouyinCreatorWork(
        id = id,
        title = title,
        coverUrl = cover,
        sourceUrl = "https://www.douyin.com/video/$id",
        mediaCount = mediaCount,
        isGallery = gallery,
    )
}
