package cn.weibo.filedownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Copies compressed samples into MP4 without decoding or reducing video quality. */
internal object DashMediaMuxer {
    fun mux(video: File, audio: File, output: File, isStopped: () -> Boolean = { false }) {
        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            fun addTrack(file: File, prefix: String): Pair<MediaExtractor, Int> {
                val extractor = MediaExtractor().also(extractors::add)
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: throw IOException("Missing $prefix track")
                extractor.selectTrack(track)
                return extractor to muxer.addTrack(extractor.getTrackFormat(track))
            }
            val tracks = listOf(addTrack(video, "video/"), addTrack(audio, "audio/"))
            muxer.start()
            // Old Android versions cannot report sample size before reading; allow large I-frames.
            var buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (isStopped()) throw IOException("Download stopped")
                val (extractor, track) = tracks.filter { it.first.sampleTrackIndex >= 0 }
                    .minByOrNull { it.first.sampleTime } ?: break
                // AAC priming samples can have negative timestamps. A negative time
                // is not end-of-stream; advance past priming instead of losing the track.
                if (extractor.sampleTime < 0) {
                    extractor.advance()
                    continue
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extractor.sampleSize > buffer.capacity()) {
                    val size = extractor.sampleSize
                    if (size > Int.MAX_VALUE) throw IOException("Media sample too large")
                    buffer = ByteBuffer.allocateDirect(size.toInt())
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) throw IOException("Unexpected end of media track")
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractors.forEach { runCatching { it.release() } }
            muxer.release()
        }
    }
}
