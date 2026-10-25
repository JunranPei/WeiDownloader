package cn.weibo.filedownloader

import java.io.InputStream

/** Some X GIF MP4s set reserved ctts flags to 1; Android rejects their entire video track.
 * Clear only that known reserved bit in a structurally valid table, without touching samples. */
internal class XGifTimingInputStream(private val source: InputStream) : InputStream() {
    private var pending = ByteArray(0)
    private var position = 0
    private var payloadRemaining = 0L
    private var passthrough = false
    private var ended = false

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (position < pending.size) {
            val count = minOf(length, pending.size - position)
            pending.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        if (ended) return -1
        if (passthrough) return source.read(buffer, offset, length)
        if (payloadRemaining > 0) {
            val count = source.read(buffer, offset, minOf(length.toLong(), payloadRemaining).toInt())
            if (count < 0) ended = true else payloadRemaining -= count
            return count
        }

        val header = readPrefix(8)
        if (header.size < 8) {
            pending = header
            ended = true
        } else {
            val smallSize = uint32(header, 0)
            val fullHeader = if (smallSize == 1L) header + readPrefix(8) else header
            val size = if (smallSize == 1L && fullHeader.size == 16) int64(fullHeader, 8) else smallSize
            val kind = String(header, 4, 4, Charsets.US_ASCII)
            if (size < fullHeader.size || (smallSize == 1L && fullHeader.size != 16)) {
                pending = fullHeader
                passthrough = true
            } else if (kind == "moov" && size <= MAX_MOOV_BYTES) {
                pending = fullHeader + readPrefix((size - fullHeader.size).toInt())
                if (pending.size.toLong() == size) {
                    val patches = mutableListOf<Int>()
                    if (findPatches(pending, fullHeader.size, pending.size, 0, patches)) {
                        patches.forEach { pending[it] = 0 }
                    }
                }
            } else {
                pending = fullHeader
                payloadRemaining = size - fullHeader.size
            }
        }
        position = 0
        return read(buffer, offset, length)
    }

    override fun close() = source.close()

    private fun readPrefix(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var count = 0
        while (count < length) {
            val read = source.read(bytes, count, length - count)
            if (read < 0) break
            if (read == 0) {
                val byte = source.read()
                if (byte < 0) break
                bytes[count++] = byte.toByte()
            } else count += read
        }
        return if (count == length) bytes else bytes.copyOf(count)
    }

    private fun findPatches(bytes: ByteArray, start: Int, end: Int, depth: Int, patches: MutableList<Int>): Boolean {
        if (depth > 8) return false
        var cursor = start
        while (cursor < end) {
            if (end - cursor < 8) return false
            val smallSize = uint32(bytes, cursor)
            val headerSize = if (smallSize == 1L) 16 else 8
            if (end - cursor < headerSize) return false
            val size = when (smallSize) {
                0L -> (end - cursor).toLong()
                1L -> int64(bytes, cursor + 8)
                else -> smallSize
            }
            if (size < headerSize || size > end - cursor) return false
            val next = cursor + size.toInt()
            val payload = cursor + headerSize
            when (String(bytes, cursor + 4, 4, Charsets.US_ASCII)) {
                "trak", "mdia", "minf", "stbl" ->
                    if (!findPatches(bytes, payload, next, depth + 1, patches)) return false
                "ctts" -> {
                    if (next - payload < 8) return false
                    val entries = uint32(bytes, payload + 4)
                    if (entries > (next - payload - 8) / 8 || entries * 8 + 8 != (next - payload).toLong()) return false
                    if (uint32(bytes, payload) == 1L) patches += payload + 3
                }
            }
            cursor = next
        }
        return true
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

    private fun int64(bytes: ByteArray, offset: Int): Long =
        (0 until 8).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 0xff) }

    private companion object {
        const val MAX_MOOV_BYTES = 4 * 1024 * 1024
    }
}
