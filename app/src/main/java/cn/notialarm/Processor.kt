package cn.notialarm

import android.content.Context
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Diag
import cn.notialarm.data.Dropped
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.SeenStore
import cn.notialarm.parse.LlmParser
import cn.notialarm.parse.TimeParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** 通知文本 -> 解析 -> 自动创建提醒。在单独线程里跑（可能要联网问大模型）。 */
object Processor {
    private val executor = Executors.newSingleThreadExecutor()
    private val recent = LinkedHashMap<String, Long>()
    private const val RECENT_MS = 90_000L
    private val LOG_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun submit(ctx: Context, incoming: Incoming, onDone: ((Reminder?, String) -> Unit)? = null) {
        executor.execute {
            val (r, msg) = runCatching { process(ctx, incoming) }
                .getOrElse { null to "出错：${it.message}" }
            onDone?.invoke(r, msg)
        }
    }

    /** 主界面手动输入用：没有包名和通知 key，来源就是输入的文本。 */
    fun submit(ctx: Context, source: String, text: String, onDone: ((Reminder?, String) -> Unit)? = null) {
        submit(
            ctx,
            Incoming("", source, "", "", text, "", null, System.currentTimeMillis()),
            onDone,
        )
    }

    private fun process(ctx: Context, incoming: Incoming): Pair<Reminder?, String> {
        val text = incoming.text
        if (text.isBlank()) return null to "通知没有正文"
        if (fastDuplicate(incoming)) return null to "重复通知，已忽略"

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
            val why = llmError?.let { "（大模型出错：$it）" }.orEmpty()
            ActivityLog.add(ctx, "${now.format(LOG_FMT)} [${incoming.source}] 未识别：$brief$why")
            DroppedStore.add(
                ctx,
                Dropped(
                    pkg = incoming.pkg,
                    app = incoming.app,
                    conversation = incoming.conversation,
                    title = incoming.title,
                    text = text,
                    reason = "没有识别到时间$why",
                ),
            )
            Diag.onDropped(ctx)
            return null to "没有识别到时间$why"
        }

        val title = result.title.ifBlank { incoming.title.ifBlank { incoming.source } }
            .ifBlank { "通知" }
        val seenKey = "${incoming.pkg}|$title|${result.time}"
        if (SeenStore.isSeen(ctx, seenKey)) {
            ActivityLog.add(ctx, "${now.format(LOG_FMT)} [${incoming.source}] 7 天内已有相同提醒，已忽略")
            return null to "已有相同提醒"
        }

        val r = Reminders.create(ctx, incoming, title, result.time, result.assumed)
        if (r == null) {
            ActivityLog.add(ctx, "${now.format(LOG_FMT)} [${incoming.source}] 已有相同提醒")
            return null to "已有相同提醒"
        }
        SeenStore.mark(ctx, seenKey)
        Diag.onCreated(ctx)
        val msg = "已创建：${Fmt.dateTime(result.time)} $title"
        ActivityLog.add(ctx, "${now.format(LOG_FMT)} [${incoming.source}] $msg（${result.matched}）")
        return r to msg
    }

    /** 同一条消息常被重复推送（通知更新、多端同步），90 秒内按 包名+正文 去重。 */
    private fun fastDuplicate(incoming: Incoming): Boolean {
        val key = "${incoming.pkg}|${TimeParser.normalize(incoming.text)}"
        synchronized(recent) {
            val now = System.currentTimeMillis()
            val it = recent.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > RECENT_MS) it.remove()
            }
            val last = recent[key]
            if (last != null && now - last <= RECENT_MS) return true
            recent[key] = now
            while (recent.size > 200) {
                val first = recent.keys.firstOrNull() ?: break
                recent.remove(first)
            }
        }
        return false
    }
}
