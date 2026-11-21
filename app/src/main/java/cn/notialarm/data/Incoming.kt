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
) {
    /** 列表里显示的来源，例如“微信 · 2026级硕士群”。 */
    val source: String
        get() = listOf(app, conversation.ifBlank { title })
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
}
