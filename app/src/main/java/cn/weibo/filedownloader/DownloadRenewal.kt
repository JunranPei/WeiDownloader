package cn.weibo.filedownloader

import java.io.IOException

/** Both the initial attempt and the bounded immediate retry resolve the durable intent. */
internal object DownloadRenewal {
    fun <T> execute(
        retryingWorker: Boolean,
        resolve: (force: Boolean) -> MediaItem,
        transfer: (MediaItem) -> T,
    ): T {
        val initial = resolve(retryingWorker)
        return try { transfer(initial) } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) throw error
            // Never go back to the queue's old URL after an authentication/expiry failure.
            transfer(resolve(true))
        }
    }
}
