package cn.notialarm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

data class Reminder(
    val id: Int,
    val title: String,
    val time: LocalDateTime,
    val offsets: List<Int>,
    val source: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 来源应用包名，用于黑名单和“打开原通知”的兜底跳转。 */
    val pkg: String = "",
    /** 会话/群聊名（微信、QQ 的群名或联系人名），用于按会话屏蔽。 */
    val conversation: String = "",
    /** 系统通知的 key，用来找回原通知的 contentIntent。 */
    val notifKey: String = "",
    /** > 0 表示已归档；列表默认不显示归档项。 */
    val archivedAt: Long = 0L,
    /** 用户点过“确认，不再提醒”。 */
    val confirmed: Boolean = false,
    /** 原文只有日期没有时刻，时间按 09:00 估的。 */
    val timeAssumed: Boolean = false,
) {
    val archived: Boolean get() = archivedAt > 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("time", time.toString())
        .put("offsets", JSONArray(offsets)).put("source", source).put("text", text)
        .put("createdAt", createdAt)
        .put("pkg", pkg).put("conversation", conversation).put("notifKey", notifKey)
        .put("archivedAt", archivedAt).put("confirmed", confirmed).put("timeAssumed", timeAssumed)

    companion object {
        fun fromJson(o: JSONObject): Reminder {
            val a = o.getJSONArray("offsets")
            return Reminder(
                o.getInt("id"), o.getString("title"), LocalDateTime.parse(o.getString("time")),
                List(a.length()) { a.getInt(it) }, o.optString("source"), o.optString("text"),
                o.optLong("createdAt"),
                pkg = o.optString("pkg"),
                conversation = o.optString("conversation"),
                notifKey = o.optString("notifKey"),
                archivedAt = o.optLong("archivedAt"),
                confirmed = o.optBoolean("confirmed"),
                timeAssumed = o.optBoolean("timeAssumed"),
            )
        }
    }
}

/** 提醒的持久化：数量不大，直接用 SharedPreferences 里的一段 JSON。 */
object ReminderStore {
    /** 事件结束后再留 2 小时，方便回看；之后自动进归档。 */
    const val ARCHIVE_GRACE_MINUTES = 120L
    /** 归档最多保留 120 条，总记录最多 200 条，避免列表无限增长。 */
    const val MAX_ARCHIVED = 120
    const val MAX_TOTAL = 200

    private fun sp(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Reminder> {
        val arr = JSONArray(sp(ctx).getString("list", "[]"))
        return List(arr.length()) { Reminder.fromJson(arr.getJSONObject(it)) }.sortedBy { it.time }
    }

    /** 待处理：按时间升序，最早要响的排最前。 */
    @Synchronized
    fun active(ctx: Context): List<Reminder> = all(ctx).filter { !it.archived }

    /** 归档：最近归档的排最前。 */
    @Synchronized
    fun archived(ctx: Context): List<Reminder> =
        all(ctx).filter { it.archived }.sortedByDescending { it.archivedAt }

    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, r: Reminder): Boolean = write(ctx, all(ctx).filter { it.id != r.id } + r)

    @Synchronized
    fun delete(ctx: Context, id: Int): Boolean = write(ctx, all(ctx).filter { it.id != id })

    @Synchronized
    fun newId(ctx: Context): Int {
        val sp = sp(ctx)
        val id = sp.getInt("next_id", 1)
        // 闹钟 requestCode = id * 10 + slot，必须留在 Int 范围内
        sp.edit().putInt("next_id", if (id >= 200_000_000) 1 else id + 1).commit()
        return id
    }

    fun isDuplicate(
        ctx: Context,
        title: String,
        time: LocalDateTime,
        conversation: String = "",
    ): Boolean = active(ctx).any {
        it.time == time &&
            it.conversation.equals(conversation, ignoreCase = true) &&
            (it.title.contains(title) || title.contains(it.title))
    }

    /**
     * 把已经结束（事件时间 + 2 小时）或用户确认过的提醒归档。
     * @return 这次新归档的 id
     */
    @Synchronized
    fun archiveExpired(ctx: Context, now: LocalDateTime = LocalDateTime.now()): List<Int> {
        val stamp = System.currentTimeMillis()
        val ids = mutableListOf<Int>()
        val out = all(ctx).map { r ->
            if (!r.archived && (r.confirmed || r.time.plusMinutes(ARCHIVE_GRACE_MINUTES).isBefore(now))) {
                ids += r.id
                r.copy(archivedAt = stamp)
            } else {
                r
            }
        }
        if (ids.isNotEmpty()) write(ctx, out)
        return ids
    }

    @Synchronized
    fun archive(ctx: Context, id: Int): Reminder? {
        val r = get(ctx, id) ?: return null
        if (r.archived) return r
        val n = r.copy(archivedAt = System.currentTimeMillis())
        write(ctx, all(ctx).filter { it.id != id } + n)
        return n
    }

    /** “确认，不再提醒”：立刻归档，并记住这是用户确认过的。 */
    @Synchronized
    fun confirm(ctx: Context, id: Int): Reminder? {
        val r = get(ctx, id) ?: return null
        val n = r.copy(archivedAt = System.currentTimeMillis(), confirmed = true)
        write(ctx, all(ctx).filter { it.id != id } + n)
        return n
    }

    /** 从归档恢复；时间已经过去的话挪到 10 分钟后，免得恢复了个不会再响的闹钟。 */
    @Synchronized
    fun restore(ctx: Context, id: Int): Reminder? {
        val r = get(ctx, id) ?: return null
        val now = LocalDateTime.now()
        val time = if (r.time.isBefore(now)) now.plusMinutes(10).withSecond(0).withNano(0) else r.time
        val n = r.copy(time = time, archivedAt = 0L, confirmed = false)
        write(ctx, all(ctx).filter { it.id != id } + n)
        return n
    }

    /**
     * 清理过久的归档记录（只清归档，绝不删除待处理）。
     *
     * 以前这里会按 event time 把 7 天前的待处理直接删掉；如果手机几天没开机，
     * 开机 rescheduleAll 会先 purge 再归档，用户就会看到“提醒凭空消失”。
     * 待处理的过期由 archiveExpired 负责转入归档，这里只负责归档保留 30 天。
     */
    @Synchronized
    fun purgeOld(ctx: Context) {
        val archiveCutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val list = all(ctx)
        val kept = list.filter { !it.archived || it.archivedAt >= archiveCutoff }
        if (kept.size != list.size) write(ctx, kept)
    }

    private fun write(ctx: Context, list: List<Reminder>): Boolean {
        val pruned = prune(list)
        val arr = JSONArray()
        pruned.forEach { arr.put(it.toJson()) }
        // 用 commit 而不是 apply：调用方需要知道是否真的写入，才能区分“未保存”和“已保存但排程失败”。
        return sp(ctx).edit().putString("list", arr.toString()).commit()
    }

    /** 归档优先淘汰最旧的；待处理永远保留（除非超过总上限）。 */
    private fun prune(list: List<Reminder>): List<Reminder> {
        val archived = list.filter { it.archived }
            .sortedByDescending { it.archivedAt }
            .take(MAX_ARCHIVED)
        val room = (MAX_TOTAL - archived.size).coerceAtLeast(0)
        val active = list.filter { !it.archived }
        val keptActive = if (active.size > room) active.sortedBy { it.createdAt }.takeLast(room) else active
        return keptActive + archived
    }
}

/** 最近处理过的通知，方便在主界面上看识别效果。 */
object ActivityLog {
    private const val MAX = 30

    @Synchronized
    fun add(ctx: Context, line: String) {
        val sp = ctx.getSharedPreferences("log", Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString("lines", "[]"))
        val out = JSONArray().put(line)
        for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.getString(i))
        sp.edit().putString("lines", out.toString()).apply()
    }

    fun lines(ctx: Context): List<String> {
        val arr = JSONArray(ctx.getSharedPreferences("log", Context.MODE_PRIVATE).getString("lines", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }
}
