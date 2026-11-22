package cn.notialarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.alarm.AlarmService
import cn.notialarm.alarm.UndoReceiver
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.ReminderStore
import cn.notialarm.ui.AlarmActivity
import cn.notialarm.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime

object Notifier {
    const val CH_ALARM = "alarm"
    const val CH_ALARM_QUIET = "alarm_quiet"
    const val CH_CREATED = "created_v2"
    const val CH_MISSED = "missed"
    const val ID_ALARM = 1
    const val ID_LISTENER_TEST = 2
    const val ID_RECORDED = 3
    const val ID_MISSED_BASE = 500_000
    const val EXTRA_EDIT_ID = "edit_id"
    const val EXTRA_JUMP = "jump"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, ctx.getString(R.string.notif_alarm_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.notif_alarm_channel_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        // 屏幕已亮、手机已解锁时用低优先级，避免通知横幅遮住正在显示的响铃页
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM_QUIET, ctx.getString(R.string.notif_alarm_quiet_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.notif_alarm_quiet_desc)
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CREATED, ctx.getString(R.string.notif_created_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.notif_created_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, ctx.getString(R.string.notif_missed_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /**
     * 所有待处理提醒只用一条通知展示（最多 6 行）。
     * 以前每识别一条就发一条通知，通知栏很快被占满。
     */
    fun recordedSummary(ctx: Context) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val active = ReminderStore.active(ctx)
        if (active.isEmpty()) {
            nm.cancel(ID_RECORDED)
            return
        }
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(ctx.getString(R.string.notif_summary_title, active.size))
        active.take(6).forEach { style.addLine("${Fmt.dateTime(it.time)} · ${it.title}") }
        if (active.size > 6) style.addLine(ctx.getString(R.string.notif_summary_more, active.size - 6))

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val first = active.first()
        val b = NotificationCompat.Builder(ctx, CH_CREATED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(ctx.getString(R.string.notif_summary_title, active.size))
            .setContentText("${Fmt.dateTime(first.time)} · ${first.title}")
            .setStyle(style)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        val lastId = Prefs(ctx).lastCreatedId
        if (lastId > 0 && ReminderStore.get(ctx, lastId)?.archived == false) {
            val undo = PendingIntent.getBroadcast(
                ctx, ID_RECORDED,
                Intent(ctx, UndoReceiver::class.java).putExtra(AlarmScheduler.EXTRA_ID, lastId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, ctx.getString(R.string.notif_undo_last), undo)
        }
        notify(ctx, ID_RECORDED, b.build())
    }

    /** 发一条自己的通知，看监听服务能不能收到。 */
    fun listenerTest(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_CREATED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(ctx.getString(R.string.notif_test_title))
            .setContentText(ctx.getString(R.string.notif_test_text))
            .setTimeoutAfter(10_000)
            .build()
        notify(ctx, ID_LISTENER_TEST, n)
    }

    fun alarm(ctx: Context, r: Reminder?, fullScreen: Boolean = true): Notification {
        val full = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ID, r?.id ?: -1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = r?.let { ctx.getString(R.string.notif_content, whenText(ctx, it), it.source, it.text) }.orEmpty()
        val b = NotificationCompat.Builder(ctx, if (fullScreen) CH_ALARM else CH_ALARM_QUIET)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(r?.title ?: ctx.getString(R.string.notif_alarm_title))
            .setContentText(r?.let { whenText(ctx, it) } ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (fullScreen) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(full)
            .setOngoing(true)
        if (fullScreen) b.setFullScreenIntent(full, true)
        if (r != null) addButtons(ctx, b, r.id)
        return b.build()
    }

    /** 响铃超时没人理时留下的通知，点开是确认弹窗。 */
    fun missed(ctx: Context, r: Reminder) {
        val open = PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, AlarmActivity::class.java).putExtra(AlarmScheduler.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(ctx.getString(R.string.notif_missed_title, r.title))
            .setContentText(whenText(ctx, r))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.notif_content, whenText(ctx, r), r.source, r.text)))
            .setContentIntent(open)
            .setAutoCancel(true)
        addButtons(ctx, b, r.id)
        // 用独立 id，避免 stopForeground 把这条“未确认”通知一起移除
        notify(ctx, ID_MISSED_BASE + r.id, b.build())
    }

    private fun addButtons(ctx: Context, b: NotificationCompat.Builder, id: Int) {
        val stop = serviceIntent(ctx, AlarmService.ACTION_STOP, id)
        val confirm = serviceIntent(ctx, AlarmService.ACTION_CONFIRM, id)
        val open = PendingIntent.getActivity(
            ctx, id + 700_000,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ID, id)
                .putExtra(EXTRA_JUMP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(0, ctx.getString(R.string.notif_action_close_once), stop)
        b.addAction(0, ctx.getString(R.string.notif_action_close_forever), confirm)
        b.addAction(0, ctx.getString(R.string.action_open_source), open)
        // 延后放在全屏页里选时长，通知栏只保留最常用的三个动作。
    }

    /** “还有 10 分钟 · 明天 周日 15:00” / “已开始 3 分钟 · …” */
    fun whenText(ctx: Context, r: Reminder): String {
        val mins = Duration.between(LocalDateTime.now(), r.time).toMinutes().toInt()
        val lead = when {
            mins >= 1 -> ctx.getString(R.string.time_remaining, Fmt.minutes(mins))
            mins <= -1 -> ctx.getString(R.string.time_started, Fmt.minutes(-mins))
            else -> ctx.getString(R.string.time_now)
        }
        return "$lead · ${Fmt.dateTime(r.time)}"
    }

    fun serviceIntent(ctx: Context, action: String, id: Int, minutes: Int = 0): PendingIntent = PendingIntent.getService(
        ctx, action.hashCode() + id * 31 + minutes,
        Intent(ctx, AlarmService::class.java).setAction(action)
            .putExtra(AlarmScheduler.EXTRA_ID, id).putExtra(AlarmService.EXTRA_MINUTES, minutes),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(ctx: Context, id: Int, n: Notification) {
        // Android 13+ 未授予通知权限时 notify 会被系统静默丢弃，不会崩溃
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
