package cn.weibo.filedownloader

import android.content.Context
import android.content.res.Configuration
/** Applies the three user-facing appearance modes without replacing the activity theme. */
object ThemeController {
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_SYSTEM = "system"

    fun wrap(context: Context): Context {
        val mode = context.getSharedPreferences("downloader_settings", Context.MODE_PRIVATE)
            .getString("theme_mode", MODE_SYSTEM).orEmpty()
        if (mode == MODE_SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        val night = if (mode == MODE_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(configuration)
    }

    fun isLight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

}
