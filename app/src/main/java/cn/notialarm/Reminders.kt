package cn.notialarm

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.notialarm.alarm.AlarmScheduler
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

    fun create(
        ctx: Context, title: String, time: LocalDateTime, source: String, text: String,
        offsets: List<Int> = Prefs(ctx).defaultOffsets,
    ): Reminder? {
        if (ReminderStore.isDuplicate(ctx, title, time)) return null
        val r = Reminder(ReminderStore.newId(ctx), title, time, offsets, source, text)
        ReminderStore.save(ctx, r)
        AlarmScheduler.schedule(ctx, r)
        Notifier.created(ctx, r)
        changed()
        return r
    }

    fun update(ctx: Context, r: Reminder) {
        AlarmScheduler.cancel(ctx, r.id)
        ReminderStore.save(ctx, r)
        AlarmScheduler.schedule(ctx, r)
        changed()
    }

    fun delete(ctx: Context, id: Int) {
        AlarmScheduler.cancel(ctx, id)
        ReminderStore.delete(ctx, id)
        Notifier.cancelCreated(ctx, id)
        changed()
    }

    fun rescheduleAll(ctx: Context) {
        ReminderStore.purgeOld(ctx)
        ReminderStore.all(ctx).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            AlarmScheduler.schedule(ctx, it)
        }
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
