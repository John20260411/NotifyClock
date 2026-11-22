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
            add(Item(activity.getString(R.string.perm_listener), activity.getString(R.string.perm_listener_desc), false) {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        } else {
            add(
                Item(
                    activity.getString(R.string.perm_listener),
                    if (NotifyListener.connected) activity.getString(R.string.perm_listener_running)
                    else activity.getString(R.string.perm_listener_disconnected),
                    NotifyListener.connected,
                ) { NotifyListener.rebind(activity) }
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Item(
                    activity.getString(R.string.perm_post), activity.getString(R.string.perm_post_desc),
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                ) { activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Item(activity.getString(R.string.perm_exact), activity.getString(R.string.perm_exact_desc), AlarmScheduler.canExact(activity)) {
                activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            add(
                Item(
                    activity.getString(R.string.perm_fullscreen), activity.getString(R.string.perm_fullscreen_desc),
                    activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
                ) { activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)) }
            )
        }
        add(
            Item(
                activity.getString(R.string.perm_overlay), activity.getString(R.string.perm_overlay_desc),
                Settings.canDrawOverlays(activity),
            ) { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }
        )
        add(
            Item(
                activity.getString(R.string.perm_battery), activity.getString(R.string.perm_battery_desc),
                activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName),
            ) { activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) }
        )
        // 国产系统特有、无法检测的权限：只给入口
        add(
            Item(
                activity.getString(R.string.perm_vendor),
                activity.getString(R.string.perm_vendor_desc),
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
                if (p.name == activity.getString(R.string.perm_vendor)) text = activity.getString(R.string.perm_vendor_button)
                setOnClickListener { runCatching { p.fix() } }
            }
            container.addView(row)
        }
    }
}
