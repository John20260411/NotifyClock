package cn.notialarm.data

import android.app.PendingIntent

/** 一条刚收到的通知，从监听服务传给解析线程。 */
data class Incoming(
    val pkg: String,
    val app: String,
    val conversation: String,
    val title: String,
    val text: String,
    val notifKey: String,
    val contentIntent: PendingIntent?,
    val postedAt: Long,
    /** 解析失败时是否写入“未记录的通知”；只有用户明确勾选的 App 才写。 */
    val recordDrop: Boolean = true,
    /** 用户在“未记录”里点“重试识别”：跳过 90 秒/7 天去重，确保这次操作真的重新解析。 */
    val force: Boolean = false,
    /** 贯穿 NotifyListener -> Processor -> Reminders 的本地追踪 id；不写用户正文。 */
    val attemptId: String = "",
) {
    /** 列表里显示的来源，例如“微信 · 2026级硕士群”。 */
    val source: String
        get() = listOf(app, conversation.ifBlank { title })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
}
