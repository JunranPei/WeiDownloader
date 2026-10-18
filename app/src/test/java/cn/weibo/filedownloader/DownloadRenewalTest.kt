package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadRenewalTest {
    private fun item(token: String) = MediaItem(MediaType.VIDEO, "https://cdn.example/video?token=$token", "", "post", 1)

    @Test fun expiryDuringTransferResolvesAgainAndNeverReusesTheFirstUrl() {
        val refreshes = mutableListOf<Boolean>()
        val urls = mutableListOf<String>()
        DownloadRenewal.execute(false, resolve = { force -> refreshes += force; item(if (force) "new" else "expired") }) {
            urls += it.url
            if (it.url.endsWith("expired")) throw IOException("HTTP 403")
        }
        assertEquals(listOf(false, true), refreshes)
        assertEquals(listOf(item("expired").url, item("new").url), urls)
    }

    @Test fun workManagerRetryStartsWithForcedResolutionAndHasABoundedImmediateRetry() {
        var transfers = 0
        val refreshes = mutableListOf<Boolean>()
        try {
            DownloadRenewal.execute(true, resolve = { refreshes += it; item("renewed") }) {
                transfers++; throw IOException("HTTP 403")
            }
            fail("Must return failure after the bounded retry")
        } catch (_: IOException) { }
        assertEquals(listOf(true, true), refreshes)
        assertEquals(2, transfers)
    }

    @Test fun resolutionFailureDoesNotDownloadAnySavedUrl() {
        var transferred = false
        try {
            DownloadRenewal.execute(false, resolve = { throw IOException("Source unavailable") }) { transferred = true }
            fail("Must not transfer without a fresh identity-checked source")
        } catch (_: IOException) { }
        assertFalse(transferred)
    }
}
