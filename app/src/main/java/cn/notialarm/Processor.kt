package cn.notialarm

import android.content.Context
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.parse.LlmParser
import cn.notialarm.parse.TimeParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** 通知文本 -> 解析 -> 自动创建提醒。在单独线程里跑（可能要联网问大模型）。 */
object Processor {
    private val executor = Executors.newSingleThreadExecutor()
    private val recent = ArrayDeque<String>()
    private val LOG_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun submit(ctx: Context, source: String, text: String, onDone: ((Reminder?, String) -> Unit)? = null) {
        executor.execute {
            val (r, msg) = runCatching { process(ctx, source, text) }
                .getOrElse { null to "出错：${it.message}" }
            onDone?.invoke(r, msg)
        }
    }

    private fun process(ctx: Context, source: String, text: String): Pair<Reminder?, String> {
        // 同一条消息常被重复推送（通知更新、多端同步），短期内去重
        val key = "$source|${TimeParser.normalize(text)}"
        synchronized(recent) {
            if (key in recent) return null to "重复通知，已忽略"
            recent.addLast(key)
            if (recent.size > 200) recent.removeFirst()
        }

        val prefs = Prefs(ctx)
        val now = LocalDateTime.now()
        val wantLlm = prefs.llmReady && TimeParser.hasTimeHint(text)
        var llmError: String? = null
        fun llm(): TimeParser.Result? = runCatching { LlmParser.parse(prefs, text, now) }
            .onFailure { llmError = it.message }.getOrNull()

        val result = when (prefs.parseMode) {
            Prefs.MODE_RULE -> TimeParser.parse(text, now)
            Prefs.MODE_LLM -> (if (wantLlm) llm() else null) ?: TimeParser.parse(text, now)
            else -> TimeParser.parse(text, now) ?: if (wantLlm) llm() else null
        }

        val brief = text.replace('\n', ' ').take(40)
        if (result == null) {
            val why = llmError?.let { "（大模型出错：$it）" } ?: ""
            ActivityLog.add(ctx, "${now.format(LOG_FMT)} [$source] 未识别：$brief$why")
            return null to "没有识别到时间$why"
        }

        val title = result.title.ifBlank { source }
        val r = Reminders.create(ctx, title, result.time, source, text)
        val msg = if (r == null) "已有相同提醒" else "已创建：${Fmt.dateTime(result.time)} $title"
        ActivityLog.add(ctx, "${now.format(LOG_FMT)} [$source] $msg（${result.matched}）")
        return r to msg
    }
}
