package cn.weibo.filedownloader

import android.app.Application
import com.google.android.material.color.DynamicColors

class AllDownloaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XiaohongshuSession.initialize(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
