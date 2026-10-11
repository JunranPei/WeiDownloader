package cn.weibo.filedownloader

import org.json.JSONObject

internal object WeiboAuthEvidence {
    fun authenticated(payload: JSONObject): Boolean? {
        if (payload.has("ok") && payload.optInt("ok", -1) != 1) return null
        val objects = listOfNotNull(payload.optJSONObject("data"), payload)
        val flags = objects.flatMap { value -> listOf("login", "islogin", "isLogin").mapNotNull { value.opt(it) as? Boolean } }
        return when {
            flags.any { !it } -> false
            flags.any { it } -> true
            else -> null
        }
    }
}

internal class WeiboVerifiedSession {
    private var fingerprint = ""
    private var verified: Boolean? = null
    private var verifiedAt = 0L

    @Synchronized
    fun record(key: String, authenticated: Boolean?, now: Long) {
        fingerprint = key
        verified = authenticated
        verifiedAt = now
    }

    @Synchronized
    fun authenticated(key: String, now: Long): Boolean? =
        verified.takeIf { key == fingerprint && now - verifiedAt in 0..60_000L }
}
