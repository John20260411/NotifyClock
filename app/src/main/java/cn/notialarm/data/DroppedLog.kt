package cn.notialarm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条“看到了但没有记录”的通知。
 * 用途：让用户知道到底是哪个 App/群聊/关键词被过滤了，而不是悄悄漏掉。
 */
data class Dropped(
    val pkg: String,
    val app: String,
    val conversation: String,
    val title: String,
    val text: String,
    val reason: String,
    val at: Long = System.currentTimeMillis(),
    val count: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("app", app).put("conversation", conversation)
        .put("title", title).put("text", text).put("reason", reason)
        .put("at", at).put("count", count)

    companion object {
        fun fromJson(o: JSONObject) = Dropped(
            o.optString("pkg"), o.optString("app"), o.optString("conversation"),
            o.optString("title"), o.optString("text"), o.optString("reason"),
            o.optLong("at"), o.optInt("count", 1),
        )
    }
}

object DroppedStore {
    private const val MAX = 30
    private fun sp(ctx: Context) = ctx.getSharedPreferences("dropped", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Dropped> {
        val arr = JSONArray(sp(ctx).getString("list", "[]"))
        return List(arr.length()) { Dropped.fromJson(arr.getJSONObject(it)) }
    }

    /** 相同来源 + 原因 + 会话合并成一条，只更新时间、次数和最后一次的正文。 */
    @Synchronized
    fun add(ctx: Context, d: Dropped) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst {
            it.pkg == d.pkg && it.reason == d.reason &&
                it.conversation == d.conversation && it.title == d.title
        }
        if (i >= 0) {
            val old = list.removeAt(i)
            list.add(0, old.copy(text = d.text, at = d.at, count = old.count + 1))
        } else {
            list.add(0, d)
        }
        write(ctx, list.take(MAX))
    }

    @Synchronized
    fun remove(ctx: Context, d: Dropped) {
        write(ctx, all(ctx).filterNot {
            it.pkg == d.pkg && it.reason == d.reason &&
                it.conversation == d.conversation && it.title == d.title
        })
    }

    @Synchronized
    fun removePkg(ctx: Context, pkg: String) {
        if (pkg.isBlank()) return
        write(ctx, all(ctx).filterNot { it.pkg == pkg })
    }

    @Synchronized
    fun clear(ctx: Context) = write(ctx, emptyList())

    private fun write(ctx: Context, list: List<Dropped>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }
}
