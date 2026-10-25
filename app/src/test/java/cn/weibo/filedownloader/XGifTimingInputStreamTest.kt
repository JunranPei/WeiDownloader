package cn.weibo.filedownloader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer

class XGifTimingInputStreamTest {
    @Test fun repairsOnlyTheReservedFlagInThePublicFailureSample() {
        val original = javaClass.getResourceAsStream("/x-gif-reserved-ctts.mp4")!!.use { it.readBytes() }
        val actual = XGifTimingInputStream(original.inputStream()).readBytes()
        assertEquals(original.size, actual.size)
        assertEquals(listOf(658), original.indices.filter { original[it] != actual[it] })
        assertEquals(0, actual[658].toInt())
        // The compressed samples and every other MP4 field remain byte-identical.
        assertArrayEquals(original.copyOfRange(1487, original.size), actual.copyOfRange(1487, actual.size))
        assertArrayEquals(actual, XGifTimingInputStream(actual.inputStream()).readBytes())
    }

    @Test fun doesNotMistakeSampleBytesForContainerMetadataAndHandlesMoovAfterMdat() {
        val sample = box("mdat", table(1))
        val original = box("ftyp", "isom0000".toByteArray()) + sample + movie(1)
        val expected = box("ftyp", "isom0000".toByteArray()) + sample + movie(0)
        assertArrayEquals(expected, XGifTimingInputStream(original.inputStream()).readBytes())
    }

    @Test fun preservesValidVersionsAndUnknownFlags() {
        for (flags in listOf(0, 2, 0x01000000, 0x01000001)) {
            val original = movie(flags)
            assertArrayEquals(original, XGifTimingInputStream(original.inputStream()).readBytes())
        }
    }

    @Test fun handlesFragmentedReadsAndExtendedBoxHeaders() {
        val original = box("moov", movie(1).copyOfRange(8, movie(1).size), extended = true)
        val expected = box("moov", movie(0).copyOfRange(8, movie(0).size), extended = true)
        val fragmented = object : FilterInputStream(original.inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(length, 3))
        }
        assertArrayEquals(expected, XGifTimingInputStream(fragmented).readBytes())
        val singleReads = XGifTimingInputStream(original.inputStream())
        assertArrayEquals(expected, generateSequence { singleReads.read().takeIf { it >= 0 } }.map(Int::toByte).toList().toByteArray())
    }

    @Test fun neverRepairsAPartialOrMalformedTable() {
        val malformed = listOf(
            movie(1).dropLast(1).toByteArray(),
            box("moov", box("trak", box("mdia", box("minf", box("stbl", box("ctts", ints(1, 2, 1, 20))))))),
            movie(1) + byteArrayOf(0, 0, 0),
        )
        for ((index, bytes) in malformed.withIndex()) {
            val actual = XGifTimingInputStream(bytes.inputStream()).readBytes()
            if (index < 2) assertArrayEquals(bytes, actual)
            else assertArrayEquals(movie(0) + byteArrayOf(0, 0, 0), actual)
        }
    }

    @Test fun boundsMemoryWithoutReadingAheadThroughLargeBoxes() {
        // Only a header exists here: returning it must not allocate or read a 1 GiB box.
        val header = ints(1024 * 1024 * 1024) + "moov".toByteArray()
        val input = XGifTimingInputStream(header.inputStream())
        assertArrayEquals(header, input.readBytes())
        val openEnded = ints(0) + "mdat".toByteArray() + movie(1)
        assertArrayEquals(openEnded, XGifTimingInputStream(openEnded.inputStream()).readBytes())
        val overflow = ints(1) + "moov".toByteArray() + ByteArray(8) { 0xff.toByte() } + movie(1)
        assertArrayEquals(overflow, XGifTimingInputStream(overflow.inputStream()).readBytes())
    }

    @Test fun preservesEmptyShortAndNonMp4StreamsAndClosesTheSource() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), "<html>denied</html>".toByteArray())) {
            assertArrayEquals(bytes, XGifTimingInputStream(bytes.inputStream()).readBytes())
        }
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true; super.close() }
        }
        XGifTimingInputStream(source).use { assertEquals(0, it.read(ByteArray(0))) }
        assertTrue(closed)
    }

    private fun movie(flags: Int) = box("moov", box("trak", box("mdia", box("minf", box("stbl", table(flags))))))
    private fun table(flags: Int) = box("ctts", ints(flags, 1, 63, 20))
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private fun box(type: String, content: ByteArray, extended: Boolean = false): ByteArray =
        if (extended) ints(1) + type.toByteArray() + ByteBuffer.allocate(8).putLong(content.size + 16L).array() + content
        else ints(content.size + 8) + type.toByteArray() + content
}
