package cn.weibo.filedownloader

import java.util.concurrent.atomic.AtomicBoolean

/** One user request owns its callbacks, including work after browser extraction. */
internal class ResolutionRequest {
    private val active = AtomicBoolean(true)
    private val pollingStarted = AtomicBoolean(false)

    fun isActive(): Boolean = active.get()
    fun startPollingOnce(): Boolean = isActive() && pollingStarted.compareAndSet(false, true)
    fun finish(): Boolean = active.compareAndSet(true, false)
    fun cancel() { active.set(false) }
}
