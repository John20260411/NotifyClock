package cn.notialarm

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Diag
import cn.notialarm.data.Dropped
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.parse.TimeParser

/** 系统把所有 App 的通知转给这里（需要用户在设置里打开“通知使用权”）。 */
class NotifyListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        connected = true
        notifyState()
        Diag.onConnected(this)
        Reminders.housekeeping(this)
        if (!replayed) {
            replayed = true
            replayActive()
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        notifyState()
        // 被系统断开（常见于覆盖安装、省电清理）时主动要求重新绑定
        runCatching { requestRebind(ComponentName(this, NotifyListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!connected) {
            connected = true
            notifyState()
        }
        handle(sbn, replay = false)
    }

    /** 连接状态变化时通知正在显示的主界面刷新，否则界面会一直停在“未连接”。 */
    private fun notifyState() {
        Handler(Looper.getMainLooper()).post { onStateChanged?.invoke() }
    }

    /**
     * 监听服务刚连上时，系统不会补发之前的通知。
     * 这里主动把最近 24 小时仍在通知栏、看起来带时间的通知再解析一遍，
     * 解决“权限明明开着，但就是漏提醒”的问题。
     */
    private fun replayActive() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val since = System.currentTimeMillis() - 24L * 3600 * 1000
        list.filter { it.postTime >= since }
            .sortedBy { it.postTime }
            .forEach { handle(it, replay = true) }
    }

    private fun handle(sbn: StatusBarNotification, replay: Boolean) {
        if (sbn.packageName == packageName) {
            if (sbn.id == Notifier.ID_LISTENER_TEST) {
                lastTestAck = System.currentTimeMillis()
                runCatching { cancelNotification(sbn.key) }
            }
            return
        }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val prefs = Prefs(this)
        val app = appLabel(sbn.packageName)
        if (!replay) Diag.onReceived(this, app)

        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty().ifBlank { rawTitle }
        val text = extractText(n)

        if (text.isBlank()) {
            drop(sbn, app, conversation, rawTitle, "", "通知没有正文（可能关闭了“通知显示消息详情”）")
            return
        }

        val hit = prefs.blacklistHit(sbn.packageName, conversation, text)
        if (hit != null) {
            // 用户主动屏蔽的内容不进入“未记录”列表，只计数，避免越积越多。
            Diag.onDropped(this)
            return
        }

        if (!prefs.monitorAll && sbn.packageName !in prefs.monitoredPackages) {
            drop(sbn, app, conversation, rawTitle, text, "这个 App 不在监听列表")
            return
        }

        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (ongoing) {
            if (TimeParser.hasTimeHint(text)) {
                drop(sbn, app, conversation, rawTitle, text, "常驻/进行中的通知，未自动创建（可在未记录列表里重试）")
            }
            return
        }

        SourceLauncher.remember(this, sbn.key, n.contentIntent)
        Diag.onAllowed(this)
        Processor.submit(
            applicationContext,
            Incoming(
                pkg = sbn.packageName,
                app = app,
                conversation = conversation,
                title = rawTitle,
                text = text,
                notifKey = sbn.key,
                contentIntent = n.contentIntent,
                postedAt = sbn.postTime,
            ),
        )
    }

    private fun drop(
        sbn: StatusBarNotification,
        app: String,
        conversation: String,
        title: String,
        text: String,
        reason: String,
    ) {
        Diag.onDropped(this)
        DroppedStore.add(
            this,
            Dropped(
                pkg = sbn.packageName,
                app = app,
                conversation = conversation,
                title = title,
                text = text,
                reason = reason,
            ),
        )
    }

    /**
     * 尽量还原通知的完整正文：
     * 聊天类通知优先取 MessagingStyle 的最后一条消息，再合并大文本/正文/子文本/多行文本。
     */
    private fun extractText(n: Notification): String {
        val parts = LinkedHashSet<String>()
        fun add(s: CharSequence?) {
            val v = s?.toString()?.trim().orEmpty()
            if (v.isNotBlank() && v != "null") parts.add(v)
        }
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()
        messaging?.messages?.lastOrNull()?.text?.let { add(it) }

        val e = n.extras
        add(e.getCharSequence(Notification.EXTRA_BIG_TEXT))
        add(e.getCharSequence(Notification.EXTRA_TEXT))
        add(e.getCharSequence(Notification.EXTRA_SUB_TEXT))
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        add(n.tickerText)

        // 大文本通常已经包含正文，去掉被其他片段完整包含的短句。
        val all = parts.toList()
        return all.filter { s -> all.none { other -> other != s && other.contains(s) } }
            .joinToString("\n")
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(pkg)

    companion object {
        /** 系统当前是否真的把通知转给了我们（权限开着不代表服务已连接）。 */
        @Volatile var connected = false

        /** 最近一次收到自检通知的时间。 */
        @Volatile var lastTestAck = 0L

        /** 监听连接状态变化时的界面回调（主线程）。 */
        @Volatile var onStateChanged: (() -> Unit)? = null

        private var instance: NotifyListener? = null
        private var replayed = false

        /** 按通知 key 找到当前还在通知栏里的那条通知（用于跳回原通知）。 */
        fun activeByKey(key: String): StatusBarNotification? {
            if (key.isBlank()) return null
            val inst = instance ?: return null
            return runCatching { inst.activeNotifications.firstOrNull { it.key == key } }.getOrNull()
        }

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
