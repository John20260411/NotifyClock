package cn.notialarm.alarm

import java.time.LocalDateTime
import java.time.LocalTime

/** 免打扰时段。纯 Kotlin，便于单元测试。start > end 表示跨午夜（如 23:00–07:00）。 */
data class QuietHours(val start: LocalTime, val end: LocalTime, val keepOnTime: Boolean = true) {

    /** t 所在的免打扰时段 [开始, 结束)，不在时段内返回 null。 */
    fun window(t: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
        if (start == end) return null
        val d = t.toLocalDate()
        val lt = t.toLocalTime()
        return if (start < end) {
            if (lt >= start && lt < end) d.atTime(start) to d.atTime(end) else null
        } else when {
            lt >= start -> d.atTime(start) to d.plusDays(1).atTime(end)
            lt < end -> d.minusDays(1).atTime(start) to d.atTime(end)
            else -> null
        }
    }

    /**
     * 把落在免打扰时段里的闹钟挪出去：
     * - 时段结束时事件还没开始 -> 挪到时段结束（醒来时提醒）
     * - 事件本身就在时段里 -> 挪到时段开始（睡前提醒）
     * keepOnTime 时，“准时”那一次照常响。
     */
    fun adjust(event: LocalDateTime, offsetMinutes: Int): LocalDateTime {
        val at = event.minusMinutes(offsetMinutes.toLong())
        if (offsetMinutes == 0 && keepOnTime) return at
        val w = window(at) ?: return at
        return if (!w.second.isAfter(event)) w.second else w.first
    }

    companion object {
        /** 计算一个提醒实际的响铃时刻（已去重、排序、去掉过去的）。 */
        fun plan(event: LocalDateTime, offsets: List<Int>, quiet: QuietHours?, now: LocalDateTime): List<LocalDateTime> =
            offsets.map { off -> quiet?.adjust(event, off) ?: event.minusMinutes(off.toLong()) }
                .filter { it.isAfter(now) }
                .distinct()
                .sorted()
    }
}
