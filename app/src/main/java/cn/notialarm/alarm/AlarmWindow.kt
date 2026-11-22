package cn.notialarm.alarm

/**
 * 判断一次闹钟触发是否仍然“新鲜”。
 *
 * 系统在关机、Doze、应用被冻结后可能补发很久以前的 PendingIntent；
 * 如果无条件响铃，用户就会在错误的时间被吵醒。这里把窗口规则集中成一个
 * 纯函数，便于单测，也避免调度器和前台服务各写一套判断。
 */
object AlarmWindow {
    /** 迟到超过 10 分钟的触发一律视为残留。 */
    const val MAX_LATE_MS = 10L * 60 * 1000

    /** 比计划时间早超过 2 分钟的触发视为异常（时钟回拨等）。 */
    const val MAX_EARLY_MS = 2L * 60 * 1000

    fun isFresh(now: Long, at: Long): Boolean {
        if (at <= 0L) return false
        return now - at <= MAX_LATE_MS && at - now <= MAX_EARLY_MS
    }
}
