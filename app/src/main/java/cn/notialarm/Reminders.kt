package cn.notialarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.ReminderStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 提醒的增删改，统一负责存储、排闹钟和刷新界面。 */
object Reminders {
    private val main = Handler(Looper.getMainLooper())

    /** 主界面在前台时设置，用来刷新列表。 */
    var onChange: (() -> Unit)? = null

    /** 从通知创建：保留包名、群聊、通知 key，供黑名单和“打开原通知”使用。 */
    fun create(
        ctx: Context,
        incoming: Incoming,
        title: String,
        time: LocalDateTime,
        timeAssumed: Boolean,
        offsets: List<Int> = Prefs(ctx).defaultOffsets,
    ): Reminder? {
        if (ReminderStore.isDuplicate(ctx, title, time)) return null
        val r = Reminder(
            id = ReminderStore.newId(ctx),
            title = title,
            time = time,
            offsets = offsets,
            source = incoming.source.ifBlank { incoming.app }.ifBlank { "通知" },
            text = incoming.text,
            pkg = incoming.pkg,
            conversation = incoming.conversation,
            notifKey = incoming.notifKey,
            timeAssumed = timeAssumed,
        )
        ReminderStore.save(ctx, r)
        AlarmScheduler.schedule(ctx, r)
        Prefs(ctx).lastCreatedId = r.id
        Notifier.recordedSummary(ctx)
        changed()
        return r
    }

    /** 主界面手动新建。 */
    fun create(
        ctx: Context, title: String, time: LocalDateTime, source: String, text: String,
        offsets: List<Int> = Prefs(ctx).defaultOffsets,
    ): Reminder? {
        if (ReminderStore.isDuplicate(ctx, title, time)) return null
        val r = Reminder(ReminderStore.newId(ctx), title, time, offsets, source, text)
        ReminderStore.save(ctx, r)
        AlarmScheduler.schedule(ctx, r)
        Prefs(ctx).lastCreatedId = r.id
        Notifier.recordedSummary(ctx)
        changed()
        return r
    }

    fun update(ctx: Context, r: Reminder) {
        AlarmScheduler.cancel(ctx, r.id)
        val n = r.copy(archivedAt = 0L, confirmed = false)
        ReminderStore.save(ctx, n)
        AlarmScheduler.schedule(ctx, n)
        Notifier.recordedSummary(ctx)
        changed()
    }

    fun delete(ctx: Context, id: Int) {
        AlarmScheduler.cancel(ctx, id)
        ReminderStore.delete(ctx, id)
        Notifier.recordedSummary(ctx)
        changed()
    }

    fun archive(ctx: Context, id: Int) {
        AlarmScheduler.cancel(ctx, id)
        ReminderStore.archive(ctx, id)
        Notifier.recordedSummary(ctx)
        changed()
    }

    /** “确认，不再提醒”：取消所有闹钟并立刻归档。 */
    fun confirm(ctx: Context, id: Int) {
        AlarmScheduler.cancel(ctx, id)
        ReminderStore.confirm(ctx, id)
        Notifier.recordedSummary(ctx)
        changed()
    }

    fun restore(ctx: Context, id: Int) {
        val n = ReminderStore.restore(ctx, id) ?: return
        AlarmScheduler.schedule(ctx, n)
        Notifier.recordedSummary(ctx)
        changed()
    }

    /** 主界面/监听服务连上时调用：自动归档过期项、清理旧数据、刷新汇总通知。 */
    fun housekeeping(ctx: Context) {
        val newIds = ReminderStore.archiveExpired(ctx)
        newIds.forEach { AlarmScheduler.cancel(ctx, it) }
        ReminderStore.purgeOld(ctx)
        Notifier.recordedSummary(ctx)
        if (newIds.isNotEmpty()) changed()
    }

    /** 设置里新增黑名单后，把已经记下的、命中的提醒批量归档。 */
    fun archiveBlacklisted(ctx: Context): Int {
        val prefs = Prefs(ctx)
        var n = 0
        ReminderStore.active(ctx).forEach { r ->
            if (prefs.blacklistHit(r.pkg, r.conversation, r.text) != null) {
                AlarmScheduler.cancel(ctx, r.id)
                ReminderStore.archive(ctx, r.id)
                n++
            }
        }
        if (n > 0) {
            Notifier.recordedSummary(ctx)
            changed()
        }
        return n
    }

    fun rescheduleAll(ctx: Context) {
        // 先归档、再清理：否则关机几天后开机，过期但未归档的 active 会被 purgeOld 直接删掉。
        val newlyArchived = ReminderStore.archiveExpired(ctx)
        newlyArchived.forEach { AlarmScheduler.cancel(ctx, it) }
        ReminderStore.purgeOld(ctx)
        ReminderStore.all(ctx).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            if (!it.archived) AlarmScheduler.schedule(ctx, it)
        }
        Notifier.recordedSummary(ctx)
        changed()
    }

    private fun changed() = main.post { onChange?.invoke() }
}

object Fmt {
    private val WEEK = arrayOf("一", "二", "三", "四", "五", "六", "日")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MD = DateTimeFormatter.ofPattern("M月d日")
    val INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** “明天 周日 15:00” / “11月5日 周四 23:59” */
    fun dateTime(t: LocalDateTime): String {
        val today = LocalDate.now()
        val day = when (t.toLocalDate()) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "后天"
            else -> t.format(MD)
        }
        return "$day 周${WEEK[t.dayOfWeek.value - 1]} ${t.format(HM)}"
    }

    /** 90 -> “1小时30分钟” */
    fun minutes(min: Int): String = when {
        min < 60 -> "${min}分钟"
        min % 1440 == 0 -> "${min / 1440}天"
        min % 60 == 0 -> "${min / 60}小时"
        else -> "${min / 60}小时${min % 60}分钟"
    }

    fun offset(min: Int): String = if (min == 0) "准时" else "提前${minutes(min)}"

    fun offsets(list: List<Int>): String = list.joinToString("、") { offset(it) }

    /** “将在 07:30、08:50、09:00 响铃”；和事件不在同一天的带上日期。 */
    fun ringTimes(event: LocalDateTime, times: List<LocalDateTime>): String {
        if (times.isEmpty()) return "没有需要响铃的时间（都已过去）"
        val md = DateTimeFormatter.ofPattern("M/d HH:mm")
        return "将在 " + times.joinToString("、") {
            if (it.toLocalDate() == event.toLocalDate()) it.format(HM) else it.format(md)
        } + " 响铃"
    }
}
