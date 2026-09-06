package cn.weibo.filedownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTest {
    @Test
    fun telegramIsTemporarilyExcludedFromVisiblePlatforms() {
        assertFalse(Platform.TELEGRAM.enabled)
        assertFalse(Platform.TELEGRAM in Platform.enabledEntries)
        assertTrue(Platform.INSTAGRAM in Platform.enabledEntries)
    }
}
