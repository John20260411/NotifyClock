package cn.notialarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class QuietHoursTest {

    private val quiet = QuietHours(LocalTime.of(23, 0), LocalTime.of(7, 30))
    private val now = LocalDateTime.of(2026, 11, 1, 20, 0)

    private fun t(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m)

    @Test fun windowCrossesMidnight() {
        assertEquals(t(1, 23) to t(2, 7, 30), quiet.window(t(1, 23, 30)))
        assertEquals(t(1, 23) to t(2, 7, 30), quiet.window(t(2, 6)))
        assertNull(quiet.window(t(2, 7, 30)))
        assertNull(quiet.window(t(1, 22, 59)))
    }

    @Test fun sameDayWindow() {
        val nap = QuietHours(LocalTime.of(12, 0), LocalTime.of(14, 0))
        assertEquals(t(2, 12) to t(2, 14), nap.window(t(2, 13)))
        assertNull(nap.window(t(2, 14)))
    }

    @Test fun earlyAlarmMovesToWakeUp() {
        // 9 点上课，提前 2 小时 = 7:00 在免打扰里 -> 挪到 7:30；提前 10 分钟不受影响
        assertEquals(
            listOf(t(2, 7, 30), t(2, 8, 50), t(2, 9)),
            QuietHours.plan(t(2, 9), listOf(120, 10, 0), quiet, now),
        )
    }

    @Test fun eventDuringSleepRemindsBeforeBed() {
        // 6 点出发：提前的闹钟挪到前一晚 23:00，准时那次照常响
        assertEquals(
            listOf(t(1, 23), t(2, 6)),
            QuietHours.plan(t(2, 6), listOf(60, 10, 0), quiet, now),
        )
    }

    @Test fun onTimeAlsoMovedWhenNotKept() {
        val strict = quiet.copy(keepOnTime = false)
        assertEquals(listOf(t(1, 23)), QuietHours.plan(t(2, 6), listOf(60, 0), strict, now))
    }

    @Test fun noQuietHours() {
        assertEquals(listOf(t(2, 5), t(2, 6)), QuietHours.plan(t(2, 6), listOf(60, 0), null, now))
    }

    @Test fun pastAlarmsDropped() {
        assertEquals(listOf(t(1, 20, 30)), QuietHours.plan(t(1, 20, 30), listOf(60, 0), quiet, now))
    }
}
