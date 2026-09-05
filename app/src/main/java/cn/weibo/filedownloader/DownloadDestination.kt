package cn.weibo.filedownloader

import android.content.SharedPreferences

object DownloadDestination {
    private const val LEGACY_PREFERENCE = "destination_uri"

    fun preference(platform: Platform): String = "${LEGACY_PREFERENCE}_${platform.key}"

    fun uri(preferences: SharedPreferences, platform: Platform): String? {
        migrateLegacyPreference(preferences)
        return preferences.getString(preference(platform), null)
    }

    fun save(preferences: SharedPreferences, platform: Platform, uri: String) {
        migrateLegacyPreference(preferences)
        preferences.edit().putString(preference(platform), uri).apply()
    }

    fun clear(preferences: SharedPreferences, platform: Platform) {
        migrateLegacyPreference(preferences)
        preferences.edit().remove(preference(platform)).apply()
    }

    private fun migrateLegacyPreference(preferences: SharedPreferences) {
        val legacyUri = preferences.getString(LEGACY_PREFERENCE, null) ?: return
        val editor = preferences.edit()
        Platform.entries.forEach { platform ->
            val platformPreference = preference(platform)
            if (!preferences.contains(platformPreference)) {
                editor.putString(platformPreference, legacyUri)
            }
        }
        editor.remove(LEGACY_PREFERENCE).apply()
    }
}
