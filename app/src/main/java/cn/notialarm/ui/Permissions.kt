package cn.notialarm.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import cn.notialarm.NotifyListener
import cn.notialarm.R
import cn.notialarm.alarm.AlarmScheduler

/**
 * 权限清单与渲染。主页只在异常时提示一行，完整清单放在设置页。
 */
object Permissions {

    class Item(val name: String, val desc: String, val granted: Boolean, val fix: () -> Unit)

    fun listenerGranted(activity: Activity): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)

    fun items(activity: Activity): List<Item> = buildList {
        val pkgUri = Uri.parse("package:${activity.packageName}")
        if (!listenerGranted(activity)) {
            add(Item("通知使用权", "读取微信、QQ 等 App 的通知", false) {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        } else {
            add(
                Item(
                    "通知使用权",
                    if (NotifyListener.connected) "监听服务运行中"
                    else "已授权，但监听服务没有连上。点“去开启”重连；还不行就在通知使用权里把本 App 关掉再打开",
                    NotifyListener.connected,
                ) { NotifyListener.rebind(activity) }
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Item(
                    "发送通知", "弹出提醒和闹钟",
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                ) { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Item("精确闹钟", "让闹钟准时响", AlarmScheduler.canExact(activity)) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            add(
                Item(
                    "全屏弹窗", "锁屏时直接弹出闹钟页面",
                    activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
                ) { activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)) }
            )
        }
        add(
            Item(
                "显示在其他应用上层（悬浮窗）", "亮屏时弹出确认窗口，没有它只会显示横幅",
                Settings.canDrawOverlays(activity),
            ) { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
        )
        add(
            Item(
                "忽略电池优化", "防止后台被系统杀掉",
                activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName),
            ) { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
        )
        // 国产系统特有、无法检测的权限：只给入口
        add(
            Item(
                "手机厂商权限（请手动确认）",
                "小米：后台弹出界面、锁屏显示、自启动；华为：应用启动管理改为手动并全部允许；OPPO/vivo：允许后台弹出、自启动",
                false,
            ) { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
        )
    }

    fun render(activity: Activity, container: LinearLayout) {
        container.removeAllViews()
        for (p in items(activity)) {
            val row = activity.layoutInflater.inflate(R.layout.item_permission, container, false)
            row.findViewById<TextView>(R.id.permName).text = (if (p.granted) "✅ " else "⚠️ ") + p.name
            row.findViewById<TextView>(R.id.permDesc).text = p.desc
            row.findViewById<Button>(R.id.permFix).apply {
                visibility = if (p.granted) View.GONE else View.VISIBLE
                if (p.name.startsWith("手机厂商")) text = "去设置"
                setOnClickListener { runCatching { p.fix() } }
            }
            container.addView(row)
        }
    }
}
