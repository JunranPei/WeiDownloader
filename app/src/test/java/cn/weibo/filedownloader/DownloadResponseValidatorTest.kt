package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResponseValidatorTest {
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
}
