package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResponseValidatorTest {
    @Test fun rejectsVideoFallbackForAnImageAndStillImageForAVideo() {
        assertTrue(DownloadResponseValidator.isWrongMediaType("video/mp4", MediaType.IMAGE))
        assertTrue(DownloadResponseValidator.isWrongMediaType("Image/JPEG; charset=binary", MediaType.LIVE_PHOTO))
        assertTrue(DownloadResponseValidator.isWrongMediaType("audio/aac", MediaType.VIDEO))
        assertFalse(DownloadResponseValidator.isWrongMediaType("application/octet-stream", MediaType.IMAGE))
        assertFalse(DownloadResponseValidator.isWrongMediaType("", MediaType.VIDEO))
    }

    @Test fun preservesDashAudioCompatibilityAndRejectsTruncatedBodies() {
        assertFalse(DownloadResponseValidator.isWrongMediaType("video/mp4", MediaType.AUDIO))
        assertFalse(DownloadResponseValidator.isWrongMediaType("audio/mp4", MediaType.AUDIO))
        assertTrue(DownloadResponseValidator.isTruncated(1_058_051, 908_828))
        assertFalse(DownloadResponseValidator.isTruncated(1_058_051, 1_058_051))
        assertFalse(DownloadResponseValidator.isTruncated(-1, 908_828))
    }

    @Test
    fun rejectsPageAndJsonContentTypesButAllowsCdnBinaryResponses() {
        assertTrue(DownloadResponseValidator.isErrorContentType("text/html; charset=utf-8"))
        assertTrue(DownloadResponseValidator.isErrorContentType("application/json"))
        assertFalse(DownloadResponseValidator.isErrorContentType("application/octet-stream"))
        assertFalse(DownloadResponseValidator.isErrorContentType("image/jpeg"))
    }

    @Test
    fun rejectsHtmlAndJsonBodiesBeforeTheyAreSavedAsMedia() {
        val html = "<!doctype html>".toByteArray()
        val json = "{\"error\":403}".toByteArray()
        assertTrue(DownloadResponseValidator.isErrorBody(html, html.size))
        assertTrue(DownloadResponseValidator.isErrorBody(json, json.size))
        assertFalse(DownloadResponseValidator.isErrorBody(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 3))
    }

    @Test
    fun rejectsPlainTextReturnedAsGenericBinaryButKeepsBinaryImageBytes() {
        val error = "upstream temporarily unavailable".toByteArray()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46)

        assertTrue(DownloadResponseValidator.isErrorBody(error, error.size, "application/octet-stream"))
        assertTrue(DownloadResponseValidator.isErrorBody(error, error.size, "image/jpeg"))
        assertFalse(DownloadResponseValidator.isErrorBody(jpeg, jpeg.size, "application/octet-stream"))
    }
}
