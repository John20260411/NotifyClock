package cn.notialarm.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmWindowTest {
    private val now = 1_800_000_000_000L

    @Test
    fun onTimeIsFresh() {
        assertTrue(AlarmWindow.isFresh(now, now))
    }

    @Test
    fun lateWithinWindowIsFresh() {
        assertTrue(AlarmWindow.isFresh(now, now - AlarmWindow.MAX_LATE_MS))
    }

    @Test
    fun lateBeyondWindowIsStale() {
        assertFalse(AlarmWindow.isFresh(now, now - AlarmWindow.MAX_LATE_MS - 1))
    }

    @Test
    fun earlyWithinWindowIsFresh() {
        assertTrue(AlarmWindow.isFresh(now, now + AlarmWindow.MAX_EARLY_MS))
    }

    @Test
    fun earlyBeyondWindowIsStale() {
        assertFalse(AlarmWindow.isFresh(now, now + AlarmWindow.MAX_EARLY_MS + 1))
    }

    @Test
    fun missingAtIsStale() {
        assertFalse(AlarmWindow.isFresh(now, 0L))
    }

    @Test
    fun futureAlarmCanBeScheduled() {
        assertTrue(AlarmWindow.canSchedule(now, now + 24L * 3600 * 1000))
    }

    @Test
    fun pastAlarmBeyondLateWindowCannotBeScheduled() {
        assertFalse(AlarmWindow.canSchedule(now, now - AlarmWindow.MAX_LATE_MS - 1))
    }

    @Test
    fun missingAtCannotBeScheduled() {
        assertFalse(AlarmWindow.canSchedule(now, 0L))
    }
}
