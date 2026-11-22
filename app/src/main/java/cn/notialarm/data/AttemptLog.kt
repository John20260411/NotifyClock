package cn.notialarm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条通知从系统回调到最终创建的本地追踪记录。
 *
 * 只保存元数据（包名、通知 key 的散列、正文长度/散列、阶段、原因码、提醒 id），
 * 不保存聊天正文或 API Key。这样既能定位断点，又不会把用户隐私写进诊断文件。
 */
object AttemptLog {
    private const val MAX = 400
    private const val TTL = 3L * 24 * 3600 * 1000
    private const val KEY = "events"
    private val seq = AtomicInteger(0)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    data class Event(
        val id: String,
        val at: Long,
        val stage: String,
        val source: String,
        val pkg: String,
        val user: Int,
        val keyHash: String,
        val postAt: Long,
        val recvAt: Long,
        val flags: Int,
        val textLen: Int,
        val textHash: String,
        val hasTime: Boolean,
        val reason: String,
        val reminderId: Int,
        val extra: String,
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("attempts", Context.MODE_PRIVATE)

    fun begin(
        ctx: Context,
        source: String,
        pkg: String,
        key: String,
        postAt: Long,
        flags: Int,
        recvAt: Long = System.currentTimeMillis(),
    ): String {
        val id = "A" + System.currentTimeMillis().toString(36).takeLast(5) +
            seq.getAndIncrement().toString(36)
        write(
            ctx,
            Event(
                id = id,
                at = recvAt,
                stage = "RECEIVED",
                source = source,
                pkg = pkg,
                user = runCatching { android.os.Process.myUserHandle().hashCode() }.getOrDefault(0),
                keyHash = shortHash(key),
                postAt = postAt,
                recvAt = recvAt,
                flags = flags,
                textLen = 0,
                textHash = "",
                hasTime = false,
                reason = "",
                reminderId = 0,
                extra = "",
            ),
        )
        return id
    }

    fun stage(
        ctx: Context,
        id: String,
        stage: String,
        reason: String = "",
        extra: String = "",
        text: String? = null,
        hasTime: Boolean? = null,
        reminderId: Int = 0,
    ) {
        if (id.isBlank()) return
        write(
            ctx,
            Event(
                id = id,
                at = System.currentTimeMillis(),
                stage = stage,
                source = "",
                pkg = "",
                user = 0,
                keyHash = "",
                postAt = 0,
                recvAt = 0,
                flags = 0,
                textLen = text?.length ?: 0,
                textHash = text?.let { shortHash(it) } ?: "",
                hasTime = hasTime ?: false,
                reason = reason.take(80),
                reminderId = reminderId,
                extra = extra.take(160),
            ),
        )
    }

    @Synchronized
    private fun write(ctx: Context, e: Event) {
        runCatching {
            val sp = sp(ctx)
            val arr = JSONArray(sp.getString(KEY, "[]"))
            val out = JSONArray().put(e.toJson())
            for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.getJSONObject(i))
            sp.edit().putString(KEY, out.toString()).apply()
        }
    }

    fun events(ctx: Context): List<Event> = runCatching {
        val arr = JSONArray(sp(ctx).getString(KEY, "[]"))
        List(arr.length()) { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 每个 attemptId 一行，显示最近阶段、原因和关联提醒 id，便于直接对照验收。 */
    fun summary(ctx: Context, limit: Int = 40): String {
        val all = events(ctx)
        if (all.isEmpty()) return "还没有通知处理记录"
        val first = LinkedHashMap<String, Event>()
        val stages = LinkedHashMap<String, MutableList<Event>>()
        for (e in all) {
            // 新事件在文件头，同一 attempt 的 RECEIVED 元数据要单独挑出来，不能取文件里第一条。
            if (e.stage == "RECEIVED") first.putIfAbsent(e.id, e)
            stages.getOrPut(e.id) { mutableListOf() }.add(e)
        }
        return stages.entries
            .sortedByDescending { it.value.maxOf { e -> e.at } }
            .take(limit)
            .joinToString("\n") { (id, list) ->
                val ordered = list.sortedBy { it.at }
                val f = first[id] ?: ordered.first()
                val last = ordered.last()
                val path = when {
                    ordered.size <= 6 -> ordered.joinToString(">") { it.stage }
                    else -> (ordered.take(2) + ordered.takeLast(3)).joinToString(">") { it.stage }
                }
                buildString {
                    append(t(f.recvAt.takeIf { it > 0 } ?: f.at))
                    append(' ')
                    append(id)
                    append(" [")
                    append(f.pkg.ifBlank { ordered.firstOrNull { it.pkg.isNotBlank() }?.pkg.orEmpty() }.ifBlank { "?" })
                    append("] ")
                    append(f.source.ifBlank { "-" })
                    append(' ')
                    append(path)
                    if (last.reason.isNotBlank()) append(" reason=").append(last.reason)
                    if (last.textLen > 0) append(" len=").append(last.textLen)
                    if (last.hasTime) append(" hint=1")
                    if (last.reminderId > 0) append(" rid=").append(last.reminderId)
                    if (last.extra.isNotBlank()) append(' ').append(last.extra)
                }
            }
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }

    private fun t(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)

    fun shortHash(s: String): String {
        if (s.isBlank()) return ""
        return runCatching {
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(10)
        }.getOrDefault("")
    }

    private fun Event.toJson(): JSONObject = JSONObject()
        .put("id", id).put("at", at).put("stage", stage).put("src", source)
        .put("pkg", pkg).put("user", user).put("key", keyHash).put("post", postAt)
        .put("recv", recvAt).put("flags", flags).put("len", textLen).put("hash", textHash)
        .put("hint", hasTime).put("reason", reason).put("rid", reminderId).put("extra", extra)

    private fun fromJson(o: JSONObject) = Event(
        o.optString("id"),
        o.optLong("at"),
        o.optString("stage"),
        o.optString("src"),
        o.optString("pkg"),
        o.optInt("user"),
        o.optString("key"),
        o.optLong("post"),
        o.optLong("recv"),
        o.optInt("flags"),
        o.optInt("len"),
        o.optString("hash"),
        o.optBoolean("hint"),
        o.optString("reason"),
        o.optInt("rid"),
        o.optString("extra"),
    )
}
