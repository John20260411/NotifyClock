package cn.notialarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每个提醒最多 9 个闹钟（slot 0..8），slot 9 留给“延后提醒”。
 * requestCode = id * 10 + slot。
 */
object AlarmScheduler {
    const val EXTRA_ID = "id"
    private const val SNOOZE_SLOT = Prefs.MAX_OFFSETS

    /** 实际响铃时刻（已按免打扰时段调整）。 */
    fun plan(ctx: Context, r: Reminder): List<LocalDateTime> =
        QuietHours.plan(r.time, r.offsets, Prefs(ctx).quietHours, LocalDateTime.now())

    fun schedule(ctx: Context, r: Reminder) {
        plan(ctx, r).take(Prefs.MAX_OFFSETS).forEachIndexed { slot, at ->
            set(ctx, millis(at), pending(ctx, r.id, slot, create = true)!!)
        }
    }

    /** 延后是用户主动选的，不受免打扰限制。 */
    fun snooze(ctx: Context, id: Int, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(ctx, at, pending(ctx, id, SNOOZE_SLOT, create = true)!!)
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (slot in 0..SNOOZE_SLOT) {
            pending(ctx, id, slot, create = false)?.let { am.cancel(it); it.cancel() }
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pending(ctx: Context, id: Int, slot: Int, create: Boolean): PendingIntent? {
        val intent = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, id * 10 + slot, intent, flags)
    }

    private fun set(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canExact(ctx)) {
            // setAlarmClock 在省电/Doze 下也会准时触发，状态栏会显示闹钟图标
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
