package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test

class ResolutionRequestTest {
    @Test fun repeatedPageEventsStartOnlyOnePollChain() {
        val request = ResolutionRequest()
        assertTrue(request.startPollingOnce())
        assertFalse(request.startPollingOnce())
        assertTrue(request.isActive())
    }

    @Test fun cancelDuringEnrichmentSuppressesLateCompletion() {
        val request = ResolutionRequest()
        request.startPollingOnce()
        request.cancel()
        assertFalse(request.finish())
        assertFalse(request.startPollingOnce())
    }

    @Test fun obsoleteRequestsCannotCompleteANewerRequest() {
        val old = ResolutionRequest()
        old.cancel()
        val current = ResolutionRequest()
        assertFalse(old.finish())
        assertTrue(current.finish())
        assertFalse(current.finish())
    }
}
