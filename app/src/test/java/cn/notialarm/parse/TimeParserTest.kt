package cn.notialarm.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class TimeParserTest {

    // 2026-10-31 是星期六
    private val now = LocalDateTime.of(2026, 10, 31, 10, 0)

    private fun at(text: String) = TimeParser.parse(text, now)?.time

    private fun dt(m: Int, d: Int, h: Int, min: Int = 0, y: Int = 2026) = LocalDateTime.of(y, m, d, h, min)

    @Test fun tomorrowAfternoon() {
        val r = TimeParser.parse("明天下午3点在正心楼开组会", now)!!
        assertEquals(dt(11, 1, 15), r.time)
        assertEquals("在正心楼开组会", r.title)
    }

    @Test fun monthDayWithClock() = assertEquals(dt(11, 5, 23, 59), at("作业截止时间：11月5日 23:59"))
    @Test fun nextWeekday() = assertEquals(dt(11, 4, 10, 30), at("下周三上午10点半考试"))
    @Test fun passedWeekdayRollsOver() = assertEquals(dt(11, 6, 9), at("周五交报告"))
    @Test fun tonight() = assertEquals(dt(10, 31, 19, 30), at("今晚7点半开班会"))
    @Test fun bareHourMeansAfternoon() = assertEquals(dt(10, 31, 15), at("3点开会"))
    @Test fun relativeMinutes() = assertEquals(dt(10, 31, 10, 30), at("30分钟后集合"))
    @Test fun relativeHalfHour() = assertEquals(dt(10, 31, 10, 30), at("半小时后出发"))
    @Test fun relativeHourAndHalf() = assertEquals(dt(10, 31, 12, 30), at("两个半小时后考试"))
    @Test fun isoDate() = assertEquals(dt(11, 10, 14), at("2026-11-10 14:00 讲座"))
    @Test fun dateOnly() = assertEquals(dt(12, 1, 9), at("12月1号交实验报告"))
    @Test fun chineseNumerals() = assertEquals(dt(10, 31, 15, 15), at("下午三点一刻在A楼开会"))
    @Test fun midnight() = assertEquals(dt(11, 1, 0), at("晚上12点前提交"))
    @Test fun noon() = assertEquals(dt(10, 31, 12), at("中午12点开会"))
    @Test fun tomorrowMorning() = assertEquals(dt(11, 1, 8), at("明早8点"))
    @Test fun dateWithPeriodOnly() = assertEquals(dt(11, 1, 14), at("明天下午开会"))
    @Test fun dayAfterTomorrow() = assertEquals(dt(11, 3, 9), at("大后天交作业"))
    @Test fun dayOfMonth() = assertEquals(dt(11, 5, 9), at("5号交作业"))
    @Test fun slashDate() = assertEquals(dt(11, 8, 18, 30), at("11/8 18:30 班会"))
    @Test fun wechatPrefix() = assertEquals(dt(11, 1, 15), at("[3条]张三: 明天3点组会"))
    @Test fun sunday() = assertEquals(dt(11, 1, 9), at("星期天交作业"))
    @Test fun earlyMorning() = assertEquals(dt(11, 1, 6), at("明天凌晨6点出发"))
    @Test fun nextMonthDay() = assertEquals(dt(11, 5, 9), at("下个月5号交材料"))
    @Test fun monthEndDeadline() = assertEquals(dt(10, 31, 23, 59), at("月底前提交申请表"))
    @Test fun nextMonthEnd() = assertEquals(dt(11, 30, 23, 59), at("下个月底前交报告"))
    @Test fun weekdayDeadline() = assertEquals(dt(11, 6, 23, 59), at("周五前交报告"))

    @Test fun assumedTimeIsMarked() {
        val r = TimeParser.parse("12月1号交实验报告", now)!!
        assertEquals(true, r.assumed)
        assertEquals(false, TimeParser.parse("11月5日 23:59 截止", now)!!.assumed)
    }

    @Test fun notTime() = assertNull(at("快一点"))
    @Test fun plainChat() = assertNull(at("收到"))
    @Test fun buildingNumber() = assertNull(at("3号楼集合"))
    @Test fun chatAboutToday() = assertNull(at("我今天吃了饭"))
    @Test fun passedTime() = assertNull(at("今天上午9点开会"))
    @Test fun timeWithoutEventWord() = assertNull(at("我3点到"))

    @Test fun hint() {
        assertEquals(true, TimeParser.hasTimeHint("下周三交论文"))
        assertEquals(false, TimeParser.hasTimeHint("好的收到"))
    }
}
