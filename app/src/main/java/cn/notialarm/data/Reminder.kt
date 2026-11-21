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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("time", time.toString())
        .put("offsets", JSONArray(offsets)).put("source", source).put("text", text)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): Reminder {
            val a = o.getJSONArray("offsets")
            return Reminder(
                o.getInt("id"), o.getString("title"), LocalDateTime.parse(o.getString("time")),
                List(a.length()) { a.getInt(it) }, o.optString("source"), o.optString("text"),
                o.optLong("createdAt"),
            )
        }
    }
}

/** 提醒的持久化：数量不大，直接用 SharedPreferences 里的一段 JSON。 */
object ReminderStore {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Reminder> {
        val arr = JSONArray(sp(ctx).getString("list", "[]"))
        return List(arr.length()) { Reminder.fromJson(arr.getJSONObject(it)) }.sortedBy { it.time }
    }

    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, r: Reminder) = write(ctx, all(ctx).filter { it.id != r.id } + r)

    @Synchronized
    fun delete(ctx: Context, id: Int) = write(ctx, all(ctx).filter { it.id != id })

    @Synchronized
    fun newId(ctx: Context): Int {
        val id = sp(ctx).getInt("next_id", 1)
        // 闹钟 requestCode = id * 10 + slot，必须留在 Int 范围内
        sp(ctx).edit().putInt("next_id", if (id >= 200_000_000) 1 else id + 1).apply()
        return id
    }

    fun isDuplicate(ctx: Context, title: String, time: LocalDateTime): Boolean =
        all(ctx).any { it.time == time && (it.title.contains(title) || title.contains(it.title)) }

    /** 清理一周前就已经结束的提醒。 */
    @Synchronized
    fun purgeOld(ctx: Context) {
        val cutoff = LocalDateTime.now().minusDays(7)
        val list = all(ctx)
        val kept = list.filter { it.time.isAfter(cutoff) }
        if (kept.size != list.size) write(ctx, kept)
    }

    private fun write(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("list", arr.toString()).apply()
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
