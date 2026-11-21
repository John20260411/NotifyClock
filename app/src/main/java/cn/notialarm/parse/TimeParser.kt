package cn.notialarm.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 本地规则解析：从一段中文通知文本里找出一个未来的时间点和事件标题。
 * 纯 Kotlin，不依赖 Android，便于单元测试。
 */
object TimeParser {

    data class Result(
        val time: LocalDateTime,
        val title: String,
        val matched: String,
        /** true = 原文只有日期没有具体时刻，时间按默认值估算。 */
        val assumed: Boolean = false,
    )

    private enum class Period(val defaultHour: Int) {
        DAWN(6), MORNING(9), NOON(12), AFTERNOON(14), EVENING(19)
    }

    private class DateHit(val date: LocalDate, val range: IntRange, val period: Period? = null)
    private class TimeHit(val hour: Int, val minute: Int, val range: IntRange, val colon: Boolean, val bare: Boolean)
    private class PeriodHit(val period: Period, val range: IntRange)

    /** 只有日期或只有时间时，必须出现这些事件词才创建提醒，避免闲聊误报。 */
    private val EVENT_WORDS = Regex(
        "截止|ddl|DDL|deadline|Deadline|交|提交|考试|测验|开会|会议|例会|组会|班会|上课|课程|讲座|报告|报名|" +
            "活动|集合|作业|实验|答辩|面试|签到|值班|培训|比赛|考核|体检|缴费|打卡|讨论|见面|出发|预约|聚餐|" +
            "返校|选课|汇报|宣讲|晚会|开始|结束|到场|参加|记得|别忘|提醒"
    )

    private val HINT = Regex(
        "\\d\\s*(点|时|号|日|月|分钟|小时|天后|:\\d)|今天|明天|后天|今晚|明晚|明早|下周|" +
            "周[1-7日天末]|星期|礼拜|截止|ddl|DDL|deadline|下个?月|月底"
    )

    private val DEADLINE = Regex("截止|ddl|DDL|deadline|Deadline")

    private val REL_HALF = Regex("(\\d+)个?半(?:小时|钟头)(?:后|之后|以后)")
    private val REL = Regex("(\\d+|半)个?(分钟|小时|钟头)(?:后|之后|以后)")
    private val DAYS_LATER = Regex("(\\d+)天(?:后|之后|以后)")
    private val FULL_DATE = Regex("(\\d{4})[年./-](\\d{1,2})[月./-](\\d{1,2})[日号]?")
    private val MONTH_DAY = Regex("(\\d{1,2})月(\\d{1,2})[日号]?")
    private val REL_MONTH = Regex("(下个?月|本月|这个月|当月)(\\d{1,2})[日号]")
    private val MONTH_END = Regex("(?:(下个?月|本月|这个月|当月)|月)底")
    private val SLASH_DATE = Regex("(?<![\\d./:-])(\\d{1,2})[/-](\\d{1,2})(?![\\d/:-])")
    private val DAY_ONLY = Regex("(?<![\\d月/.-])(\\d{1,2})[日号](?![楼线门床位机房院馆厅口窗车道街路栋舍层室\\d])")
    private val REL_DAY = Regex("大后天|今天|今日|今晚|今早|今夜|明天|明日|明早|明晚|后天")
    private val WEEK = Regex("(下下个?|下个?|这个?|本|上个?)?(?:周|星期|礼拜)([1-7日天末])")
    private val CLOCK = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
    private val DIAN = Regex("(?<!\\d)(\\d{1,2})[点时](?:(1)刻|(3)刻|(半)|(\\d{1,2})分?|整|钟)?")
    private val PERIOD = Regex("凌晨|早上|早晨|清晨|上午|中午|午后|下午|傍晚|晚上|夜里|夜间|今晚|明晚|今早|明早|晚|早")
    private val CN_NUM = Regex("[零〇一二两三四五六七八九十]+")

    fun hasTimeHint(raw: String): Boolean = HINT.containsMatchIn(normalize(raw))

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Result? {
        val text = normalize(raw)
        val today = now.toLocalDate()

        REL_HALF.find(text)?.let { m ->
            return build(text, listOf(m.range), now.plusMinutes(m.groupValues[1].toLong() * 60 + 30))
        }
        REL.find(text)?.let { m ->
            val unit = if (m.groupValues[2] == "分钟") 1.0 else 60.0
            val n = if (m.groupValues[1] == "半") 0.5 else m.groupValues[1].toDouble()
            val minutes = (n * unit).toLong()
            if (minutes > 0) return build(text, listOf(m.range), now.plusMinutes(minutes))
        }

        val date = findDates(text, today).minByOrNull { it.range.first }
        var time = findTimes(text, date).let { all ->
            all.firstOrNull { date == null || it.range.first >= date.range.first } ?: all.firstOrNull()
        }
        var period = time?.let { periodBefore(text, it.range.first) }
        // “快一点”之类：光秃秃的 1点，没有日期也没有上午/下午，多半不是时间
        if (time != null && time.bare && time.hour == 1 && period == null && date == null) {
            time = null
            period = null
        }
        if (date == null && time == null) return null
        if ((date == null || time == null) && !EVENT_WORDS.containsMatchIn(text)) return null

        val ranges = mutableListOf<IntRange>()
        val baseDate = date?.date ?: today
        var dt: LocalDateTime
        var assumed = false
        if (time != null) {
            val p = period?.period ?: date?.period
            dt = baseDate.atStartOfDay()
                .plusHours(adjustHour(time.hour, p, time.colon).toLong())
                .plusMinutes(time.minute.toLong())
            ranges += time.range
            period?.let { ranges += it.range }
        } else {
            val after = periodAfter(text, date!!.range.last + 1)
            val p = after?.period ?: date.period ?: Period.MORNING
            // “周五前交”“月底截止”：只有日期时，截止类按当天 23:59，其余按上午 9:00。
            val deadline = DEADLINE.containsMatchIn(text) ||
                text.substring(date.range.last + 1).trimStart().startsWith("前")
            dt = if (deadline) baseDate.atTime(23, 59) else baseDate.atTime(p.defaultHour, 0)
            assumed = !deadline
            after?.let { ranges += it.range }
        }
        date?.let { ranges += it.range }
        if (date == null && dt.isBefore(now)) dt = dt.plusDays(1)
        if (dt.isBefore(now.minusMinutes(1))) return null
        return build(text, ranges, dt, assumed)
    }

    private fun findDates(text: String, today: LocalDate): List<DateHit> {
        val hits = mutableListOf<DateHit>()
        fun add(range: IntRange, date: LocalDate?, period: Period? = null) {
            if (date == null) return
            if (hits.any { it.range.first <= range.last && range.first <= it.range.last }) return
            hits += DateHit(date, range, period)
        }
        fun yearly(m: Int, d: Int): LocalDate? {
            val x = safeDate(today.year, m, d) ?: return null
            return if (x.isBefore(today)) x.plusYears(1) else x
        }

        FULL_DATE.findAll(text).forEach { m ->
            val (y, mo, d) = m.destructured
            add(m.range, safeDate(y.toInt(), mo.toInt(), d.toInt()))
        }
        MONTH_DAY.findAll(text).forEach { m -> add(m.range, yearly(m.groupValues[1].toInt(), m.groupValues[2].toInt())) }
        REL_MONTH.findAll(text).forEach { m ->
            val base = if (m.groupValues[1].startsWith("下")) today.plusMonths(1) else today
            var d = safeDate(base.year, base.monthValue, m.groupValues[2].toInt())
            if (d != null && d.isBefore(today)) d = d.plusMonths(1)
            add(m.range, d)
        }
        MONTH_END.findAll(text).forEach { m ->
            val base = if (m.groupValues[1].startsWith("下")) today.plusMonths(1) else today
            add(m.range, base.withDayOfMonth(base.lengthOfMonth()))
        }
        SLASH_DATE.findAll(text).forEach { m -> add(m.range, yearly(m.groupValues[1].toInt(), m.groupValues[2].toInt())) }
        DAY_ONLY.findAll(text).forEach { m ->
            val d = m.groupValues[1].toInt()
            val thisMonth = safeDate(today.year, today.monthValue, d)
            val date = if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth else {
                val next = today.plusMonths(1)
                safeDate(next.year, next.monthValue, d)
            }
            add(m.range, date)
        }
        REL_DAY.findAll(text).forEach { m ->
            val (offset, period) = when (m.value) {
                "今天", "今日" -> 0L to null
                "今晚", "今夜" -> 0L to Period.EVENING
                "今早" -> 0L to Period.MORNING
                "明天", "明日" -> 1L to null
                "明早" -> 1L to Period.MORNING
                "明晚" -> 1L to Period.EVENING
                "后天" -> 2L to null
                else -> 3L to null // 大后天
            }
            add(m.range, today.plusDays(offset), period)
        }
        DAYS_LATER.findAll(text).forEach { m -> add(m.range, today.plusDays(m.groupValues[1].toLong())) }
        WEEK.findAll(text).forEach { m ->
            val dow = when (val c = m.groupValues[2]) {
                "日", "天" -> 7
                "末" -> 6
                else -> c.toInt()
            }
            val monday = today.with(DayOfWeek.MONDAY)
            val prefix = m.groupValues[1]
            val date = when {
                prefix.startsWith("下下") -> monday.plusWeeks(2).plusDays(dow - 1L)
                prefix.startsWith("下") -> monday.plusWeeks(1).plusDays(dow - 1L)
                prefix.startsWith("上") -> monday.minusWeeks(1).plusDays(dow - 1L)
                prefix.isNotEmpty() -> monday.plusDays(dow - 1L)
                else -> monday.plusDays(dow - 1L).let { if (it.isBefore(today)) it.plusWeeks(1) else it }
            }
            add(m.range, date)
        }
        return hits
    }

    private fun findTimes(text: String, date: DateHit?): List<TimeHit> {
        val hits = mutableListOf<TimeHit>()
        fun free(r: IntRange) = date == null || r.last < date.range.first || r.first > date.range.last
        CLOCK.findAll(text).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h <= 24 && min < 60 && free(m.range)) hits += TimeHit(h, min, m.range, colon = true, bare = false)
        }
        DIAN.findAll(text).forEach { m ->
            val h = m.groupValues[1].toInt()
            val min = when {
                m.groupValues[2].isNotEmpty() -> 15
                m.groupValues[3].isNotEmpty() -> 45
                m.groupValues[4].isNotEmpty() -> 30
                m.groupValues[5].isNotEmpty() -> m.groupValues[5].toInt()
                else -> 0
            }
            val bare = m.value.length == m.groupValues[1].length + 1
            if (h <= 24 && min < 60 && free(m.range)) hits += TimeHit(h, min, m.range, colon = false, bare = bare)
        }
        return hits.sortedBy { it.range.first }
    }

    private fun periodOf(word: String): Period = when (word) {
        "凌晨" -> Period.DAWN
        "中午" -> Period.NOON
        "午后", "下午" -> Period.AFTERNOON
        "傍晚", "晚上", "夜里", "夜间", "今晚", "明晚", "晚" -> Period.EVENING
        else -> Period.MORNING
    }

    /** 紧挨在时间前面（最多隔 2 个字）的时段词。 */
    private fun periodBefore(text: String, start: Int): PeriodHit? {
        val from = maxOf(0, start - 6)
        return PERIOD.findAll(text.substring(from, start))
            .lastOrNull { from + it.range.last + 1 >= start - 2 }
            ?.let { PeriodHit(periodOf(it.value), (from + it.range.first)..(from + it.range.last)) }
    }

    /** 紧跟在日期后面的时段词，如“明天下午开会”。 */
    private fun periodAfter(text: String, end: Int): PeriodHit? {
        val m = PERIOD.find(text, end) ?: return null
        return if (m.range.first <= end + 2) PeriodHit(periodOf(m.value), m.range) else null
    }

    private fun adjustHour(h: Int, p: Period?, colon: Boolean): Int = when (p) {
        Period.DAWN -> if (h == 12) 0 else h
        Period.MORNING -> h
        Period.NOON -> if (h in 1..4) h + 12 else h
        Period.AFTERNOON -> if (h in 1..11) h + 12 else h
        Period.EVENING -> if (h in 1..11) h + 12 else if (h == 12) 24 else h
        // 没说上午下午：写成 3:00 的照字面，写成“3点”的按下午理解（很少有人约凌晨 3 点）
        null -> if (!colon && h in 1..6) h + 12 else h
    }

    private fun build(text: String, ranges: List<IntRange>, dt: LocalDateTime, assumed: Boolean = false): Result {
        val sb = StringBuilder(text)
        ranges.sortedByDescending { it.first }.forEach { sb.delete(it.first, it.last + 1) }
        val title = sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\s,，。.:：!！、;；~～]+|[\\s,，。.:：!！、;；~～]+$"), "")
            .take(30)
        val matched = ranges.sortedBy { it.first }.joinToString(" ") { text.substring(it) }
        return Result(dt, title, matched, assumed)
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '：' -> ':'
                    '－' -> '-'
                    '／' -> '/'
                    '　' -> ' '
                    else -> c
                }
            )
        }
        val t = sb.toString().replace(Regex("^\\[\\d+条]\\s*"), "")
        return CN_NUM.replace(t) { m -> cnToInt(m.value)?.toString() ?: m.value }
    }

    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private fun cnToInt(s: String): Int? {
        if ('十' !in s) {
            if (s.length > 4) return null
            return s.fold(0) { acc, c -> acc * 10 + CN_DIGITS.getValue(c) }
        }
        var total = 0
        var cur = 0
        for (c in s) {
            if (c == '十') {
                total += (if (cur == 0) 1 else cur) * 10
                cur = 0
            } else {
                cur = CN_DIGITS.getValue(c)
            }
        }
        return total + cur
    }
}
