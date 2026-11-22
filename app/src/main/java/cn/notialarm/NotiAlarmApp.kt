package cn.notialarm

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cn.notialarm.data.Prefs

/** 应用启动时恢复用户选择的界面语言。 */
class NotiAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val tag = Prefs(this).language
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
        applyLauncher(tag)
    }

    /** 桌面图标名称：中文用“通知闹钟”，英文用 NotifyClock。 */
    private fun applyLauncher(language: String) {
        val english = when (language) {
            "en" -> true
            "zh" -> false
            else -> java.util.Locale.getDefault().language == "en"
        }
        val pm = packageManager
        val zh = ComponentName(this, "cn.notialarm.LauncherZh")
        val en = ComponentName(this, "cn.notialarm.LauncherEn")
        runCatching {
            pm.setComponentEnabledSetting(
                zh,
                if (english) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                en,
                if (english) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { android.util.Log.e("NotiAlarm", "applyLauncher", it) }
    }

    companion object {
        lateinit var appContext: android.content.Context
            private set

        fun applyLanguage(language: String) {
            val locales = if (language.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language)
            }
            AppCompatDelegate.setApplicationLocales(locales)
            appContext?.let { ctx ->
                val english = when (language) {
                    "en" -> true
                    "zh" -> false
                    else -> java.util.Locale.getDefault().language == "en"
                }
                val pm = ctx.packageManager
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(ctx, "cn.notialarm.LauncherZh"),
                        if (english) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    pm.setComponentEnabledSetting(
                        ComponentName(ctx, "cn.notialarm.LauncherEn"),
                        if (english) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }.onFailure { android.util.Log.e("NotiAlarm", "applyLanguage", it) }
            }
        }
    }
}
