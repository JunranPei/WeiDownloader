package cn.weibo.filedownloader

import org.json.JSONObject

internal object WeiboApiResponse {
    fun errorMessage(payload: JSONObject, fallback: String, unavailable: String): String {
        val code = sequenceOf("error_code", "errno").map { payload.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "null" && it != "0" }
        // Server messages have their own language. Display app resources so
        // English and Chinese interfaces never inherit the server's locale.
        return if (code == "20112") unavailable else fallback
    }
}
