package cn.notialarm

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Prefs

/** 系统把所有 App 的通知转给这里（需要用户在设置里打开“通知使用权”）。 */
class NotifyListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        // 被系统断开（常见于覆盖安装、省电清理）时主动要求重新绑定
        runCatching { requestRebind(ComponentName(this, NotifyListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        connected = true
        if (sbn.packageName == packageName) {
            if (sbn.id == Notifier.ID_LISTENER_TEST) {
                lastTestAck = System.currentTimeMillis()
                runCatching { cancelNotification(sbn.key) }
            }
            return
        }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (sbn.packageName !in Prefs(this).monitoredPackages) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val source = listOf(appLabel(sbn.packageName), title).filter { it.isNotBlank() }.joinToString(" · ")
        if (text.isBlank()) {
            ActivityLog.add(this, "[$source] 通知没有正文（检查该 App 是否开启了“通知显示消息详情”）")
            return
        }
        Processor.submit(applicationContext, source, text)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)

    companion object {
        /** 系统当前是否真的把通知转给了我们（权限开着不代表服务已连接）。 */
        @Volatile var connected = false

        /** 最近一次收到自检通知的时间。 */
        @Volatile var lastTestAck = 0L

        /**
         * 强制系统重新绑定监听服务。
         * 覆盖安装 App 后很多手机不会自动重连，表现为权限开着但收不到任何通知。
         */
        fun rebind(ctx: Context) {
            val cn = ComponentName(ctx, NotifyListener::class.java)
            val pm = ctx.packageManager
            runCatching {
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            runCatching { requestRebind(cn) }
        }
    }
}
