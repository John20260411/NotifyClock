package cn.notialarm

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
        val prefs = Prefs(this)
        val app = appLabel(sbn.packageName)
        if (!replay) Diag.onReceived(this, app)

        // 只处理用户明确勾选监听的 App。未勾选的 App 直接丢弃，连“未记录”都不写，
        // 否则系统通知、键盘提示等无关内容会把“未记录的通知”列表塞满。
        val explicitlyMonitored = sbn.packageName in prefs.monitoredPackages
        if (!prefs.monitorAll && !explicitlyMonitored) return

        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim().orEmpty()
            .ifBlank { messaging?.conversationTitle?.toString()?.trim().orEmpty() }
            .ifBlank { extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty() }
            .ifBlank { rawTitle }
        val text = extractText(n, messaging)
        val groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val hasTime = TimeParser.hasTimeHint(text)

        if (text.isBlank()) {
            drop(
                sbn, app, conversation, rawTitle, "", "通知没有正文（可能关闭了“通知显示消息详情”）",
                recordDrop = explicitlyMonitored,
            )
            return
        }

        // 群聊汇总通常只有“3 条新消息”，没有可解析的时间；有时间的汇总仍尝试解析。
        // 明确勾选监听的 App 被这里挡下时写入“未记录”，否则用户只看到“没反应”。
        if (groupSummary && !hasTime) {
            drop(
                sbn, app, conversation, rawTitle, text, "群聊汇总里没有可识别的时间",
                recordDrop = explicitlyMonitored,
            )
            return
        }

        val hit = prefs.blacklistHit(sbn.packageName, conversation, text)
        if (hit != null) {
            // 用户主动屏蔽的内容不进入“未记录”列表，只计数，避免越积越多。
            Diag.onDropped(this)
            return
        }

        // 常驻通知多数是播放器/前台服务；只有确实带时间时才尝试创建，
        // 避免微信“正在运行”之类的常驻通知被误当成提醒。
        if (ongoing && !hasTime) {
            drop(
                sbn, app, conversation, rawTitle, text, "常驻通知里没有可识别的时间",
                recordDrop = explicitlyMonitored,
            )
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
                recordDrop = explicitlyMonitored,
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
        recordDrop: Boolean,
    ) {
        Diag.onDropped(this)
        if (!recordDrop) return
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
     * 聊天类通知优先取 MessagingStyle 的最后几条消息，再合并大文本/正文/子文本/多行文本。
     * 只取最后一条会漏掉“上一条是图片、这一条才是文字”的情况，所以保留最近 3 条。
     */
    private fun extractText(n: Notification, messaging: NotificationCompat.MessagingStyle?): String {
        val parts = LinkedHashSet<String>()
        fun add(s: CharSequence?) {
            val v = s?.toString()?.trim().orEmpty()
            if (v.isNotBlank() && v != "null") parts.add(v)
        }
        messaging?.messages?.takeLast(3)?.forEach { add(it.text) }
        // 部分微信/QQ 版本用自定义 Parcelable 存消息，NotificationCompat 解析不到；
        // 直接读系统 EXTRA_MESSAGES，避免“通知收到了但正文为空”。
        runCatching {
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.takeLast(3)
                ?.forEach { raw ->
                    (raw as? Bundle)?.getCharSequence("text")?.let { add(it) }
                }
        }

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
