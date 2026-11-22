package cn.notialarm

import android.content.Context
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.AttemptLog
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
        val attemptId = incoming.attemptId.ifBlank {
            AttemptLog.begin(
                ctx,
                when {
                    incoming.force -> "RETRY"
                    incoming.pkg.isBlank() && incoming.notifKey.isBlank() -> "MANUAL"
                    else -> "LIVE"
                },
                incoming.pkg,
                incoming.notifKey,
                incoming.postedAt,
                0,
            )
        }
        AttemptLog.stage(ctx, attemptId, "QUEUED")
        val queuedAt = System.currentTimeMillis()
        executor.execute {
            val (r, msg) = try {
                process(ctx, incoming.copy(attemptId = attemptId), queuedAt)
            } catch (t: Throwable) {
                AttemptLog.stage(
                    ctx, attemptId, "FAILED", reason = "EXCEPTION",
                    extra = "${t.javaClass.simpleName}: ${t.message.orEmpty().take(80)}",
                )
                ActivityLog.add(ctx, ctx.getString(R.string.proc_error, nowText(), attemptId, t.javaClass.simpleName))
                null to ctx.getString(R.string.proc_error_toast, t.message.orEmpty().take(40))
            }
            onDone?.invoke(r, msg)
        }
    }

    /** 主界面手动输入用：没有包名和通知 key，来源就是输入的文本。 */
    fun submit(ctx: Context, source: String, text: String, onDone: ((Reminder?, String) -> Unit)? = null) {
        submit(
            ctx,
            Incoming("", source, "", "", text, "", null, System.currentTimeMillis(), recordDrop = false),
            onDone,
        )
    }

    private fun process(ctx: Context, incoming: Incoming, queuedAt: Long): Pair<Reminder?, String> {
        val attemptId = incoming.attemptId
        val text = incoming.text
        if (text.isBlank()) {
            AttemptLog.stage(ctx, attemptId, "FILTERED", reason = "EMPTY_TEXT")
            return null to ctx.getString(R.string.proc_no_body)
        }
        // 主界面“试一试”没有包名，属于用户主动操作；重试也跳过去重，
        // 否则用户删掉提醒后再试一次会被 7 天去重挡掉，看起来像“没有创建”。
        val manual = (incoming.pkg.isBlank() && incoming.notifKey.isBlank()) || incoming.force
        val fastKey = if (manual) null else fastKey(incoming)
        if (fastKey != null && fastDuplicate(fastKey)) {
            AttemptLog.stage(ctx, attemptId, "FILTERED", reason = "FAST_DUPLICATE", text = text)
            ActivityLog.add(ctx, "${nowText()} [$attemptId] ${ctx.getString(R.string.proc_dup_90)}")
            return null to ctx.getString(R.string.proc_dup_90_toast)
        }

        val prefs = Prefs(ctx)
        val now = LocalDateTime.now()
        val wantLlm = prefs.llmReady && TimeParser.hasTimeHint(text)
        var llmError: String? = null
        fun llm(): TimeParser.Result? = runCatching { LlmParser.parse(prefs, text, now) }
            .onFailure { llmError = it.message }.getOrNull()

        AttemptLog.stage(
            ctx, attemptId, "PARSE_START",
            extra = "mode=${prefs.parseMode} queueMs=${System.currentTimeMillis() - queuedAt} llm=$wantLlm",
            text = text, hasTime = TimeParser.hasTimeHint(text),
        )
        val result = when (prefs.parseMode) {
            Prefs.MODE_RULE -> TimeParser.parse(text, now)
            Prefs.MODE_LLM -> (if (wantLlm) llm() else null) ?: TimeParser.parse(text, now)
            else -> TimeParser.parse(text, now) ?: if (wantLlm) llm() else null
        }

        val brief = text.replace('\n', ' ').take(40)
        if (result == null) {
            fastKey?.let { releaseFast(it) }
            val why = llmError?.let { ctx.getString(R.string.proc_llm_error, it) }.orEmpty()
            AttemptLog.stage(
                ctx, attemptId, "PARSE_FAIL", reason = "PARSE_NO_TIME",
                extra = "llmErr=${llmError.orEmpty().take(80)}",
                text = text, hasTime = false,
            )
            ActivityLog.add(ctx, ctx.getString(R.string.proc_unrecognized, nowText(), attemptId, incoming.source, "$brief$why"))
            if (incoming.recordDrop) {
                DroppedStore.add(
                    ctx,
                    Dropped(
                        pkg = incoming.pkg,
                        app = incoming.app,
                        conversation = incoming.conversation,
                        title = incoming.title,
                        text = text,
                        reason = ctx.getString(R.string.proc_no_time, why),
                    ),
                )
                Diag.onDropped(ctx)
            }
            return null to ctx.getString(R.string.proc_no_time, why)
        }
        AttemptLog.stage(
            ctx, attemptId, "PARSE_OK",
            extra = "time=${result.time} matchedLen=${result.matched.length} assumed=${result.assumed}",
            text = text, hasTime = true,
        )

        val title = result.title.ifBlank { incoming.title.ifBlank { incoming.source } }
            .ifBlank { ctx.getString(R.string.notif_alarm_title) }
        // 不同群聊里同一句话不能被当成同一条提醒：把会话名纳入去重键。
        val seenKey = "${incoming.pkg}|${incoming.conversation}|$title|${result.time}"
        if (!manual && SeenStore.isSeen(ctx, seenKey)) {
            AttemptLog.stage(
                ctx, attemptId, "FILTERED", reason = "SEEN_DUPLICATE",
                extra = "seenKey=${AttemptLog.shortHash(seenKey)}",
            )
            ActivityLog.add(ctx, ctx.getString(R.string.proc_seen, nowText(), "$attemptId] [${incoming.source}"))
            return null to ctx.getString(R.string.proc_same)
        }

        val outcome = Reminders.createDetailed(ctx, incoming, title, result.time, result.assumed)
        val r = outcome.reminder
        if (r != null && !manual) SeenStore.mark(ctx, seenKey)

        when (outcome.status) {
            Reminders.CreateStatus.ACTIVE_DUPLICATE -> {
                AttemptLog.stage(ctx, attemptId, "FILTERED", reason = "ACTIVE_DUPLICATE")
                ActivityLog.add(ctx, ctx.getString(R.string.proc_seen, nowText(), "$attemptId] [${incoming.source}"))
                return null to ctx.getString(R.string.proc_same)
            }
            Reminders.CreateStatus.PERSIST_FAILED -> {
                fastKey?.let { releaseFast(it) }
                AttemptLog.stage(
                    ctx, attemptId, "FAILED", reason = "PERSIST_FAILED",
                    extra = outcome.error.orEmpty(),
                )
                ActivityLog.add(ctx, ctx.getString(R.string.proc_save_failed, nowText(), "$attemptId] [${incoming.source}"))
                return null to ctx.getString(R.string.proc_save_failed_toast)
            }
            Reminders.CreateStatus.SCHEDULE_FAILED -> {
                Diag.onCreated(ctx)
                AttemptLog.stage(
                    ctx, attemptId, "CREATED", reason = "SCHEDULE_FAILED",
                    extra = "${outcome.error.orEmpty()} slots=${outcome.scheduled}", reminderId = r!!.id,
                )
                val msg = ctx.getString(R.string.proc_schedule_failed, outcome.error.orEmpty().take(50))
                ActivityLog.add(ctx, "${nowText()} [$attemptId] [${incoming.source}] $msg（${result.matched}）")
                return r to msg
            }
            Reminders.CreateStatus.NO_FUTURE_ALARM -> {
                Diag.onCreated(ctx)
                AttemptLog.stage(
                    ctx, attemptId, "CREATED", reason = "NO_FUTURE_ALARM",
                    reminderId = r!!.id,
                )
                val msg = ctx.getString(R.string.proc_no_future)
                ActivityLog.add(ctx, "${nowText()} [$attemptId] [${incoming.source}] $msg（${result.matched}）")
                return r to msg
            }
            Reminders.CreateStatus.CREATED -> {
                Diag.onCreated(ctx)
                AttemptLog.stage(
                    ctx, attemptId, "CREATED", reason = "CREATED",
                    extra = "slots=${outcome.scheduled}", reminderId = r!!.id,
                )
                val msg = ctx.getString(R.string.proc_created, Fmt.dateTime(result.time), title)
                ActivityLog.add(ctx, "${nowText()} [$attemptId] [${incoming.source}] $msg（${result.matched}）")
                return r to msg
            }
        }
    }

    private fun nowText(): String = LocalDateTime.now().format(LOG_FMT)

    /** 同一个 App 的同一条通知更新才去重；不同会话同文不能互相吞掉。 */
    private fun fastKey(incoming: Incoming): String {
        val scope = incoming.notifKey.ifBlank { incoming.conversation }
        return "${incoming.pkg}|$scope|${TimeParser.normalize(incoming.text)}"
    }

    /** 90 秒内按 包名+通知 key/会话+正文 去重；解析失败时由调用方释放，允许重试。 */
    private fun fastDuplicate(key: String): Boolean {
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

    private fun releaseFast(key: String) {
        synchronized(recent) { recent.remove(key) }
    }
}
