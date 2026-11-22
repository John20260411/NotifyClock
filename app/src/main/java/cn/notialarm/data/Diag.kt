package cn.notialarm.data

import android.content.Context
import android.content.SharedPreferences
import cn.notialarm.R
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 通知链路的计数器和最近状态。
 * 用户反馈“收不到提醒”时，先看这里：到底收到了几条、几条进了监听列表、几条没记录。
 */
object Diag {
    private const val KEY_DAY = "day"
    private const val KEY_RECEIVED = "received"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_CREATED = "created"
    private const val KEY_DROPPED = "dropped"
    private const val KEY_LAST_APP = "last_app"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_CONNECTED_AT = "connected_at"
    private val FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)

    private fun today() = LocalDate.now().toString()

    private fun bump(ctx: Context, key: String, by: Int = 1) {
        val sp = sp(ctx)
        val e = sp.edit()
        val fresh = sp.getString(KEY_DAY, "") != today()
        if (fresh) {
            e.putString(KEY_DAY, today())
            e.putInt(KEY_RECEIVED, 0).putInt(KEY_ALLOWED, 0)
            e.putInt(KEY_CREATED, 0).putInt(KEY_DROPPED, 0)
        }
        val base = if (fresh) 0 else sp.getInt(key, 0)
        e.putInt(key, base + by).apply()
    }

    fun onReceived(ctx: Context, app: String) {
        bump(ctx, KEY_RECEIVED)
        sp(ctx).edit().putString(KEY_LAST_APP, app)
            .putLong(KEY_LAST_AT, System.currentTimeMillis()).apply()
    }

    fun onAllowed(ctx: Context) = bump(ctx, KEY_ALLOWED)

    fun onCreated(ctx: Context) = bump(ctx, KEY_CREATED)

    fun onDropped(ctx: Context) = bump(ctx, KEY_DROPPED)

    fun onConnected(ctx: Context) {
        sp(ctx).edit().putLong(KEY_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    private fun n(sp: SharedPreferences, key: String, fresh: Boolean) = if (fresh) 0 else sp.getInt(key, 0)

    private fun t(ms: Long) = if (ms <= 0) "—" else
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(FMT)

    /** 主界面顶部的一行诊断信息。 */
    fun summary(ctx: Context, connected: Boolean): String {
        val sp = sp(ctx)
        val fresh = sp.getString(KEY_DAY, "") != today()
        val received = n(sp, KEY_RECEIVED, fresh)
        val allowed = n(sp, KEY_ALLOWED, fresh)
        val created = n(sp, KEY_CREATED, fresh)
        val dropped = n(sp, KEY_DROPPED, fresh)
        val lastApp = if (fresh) "" else sp.getString(KEY_LAST_APP, "").orEmpty()
        val lastAt = if (fresh) 0L else sp.getLong(KEY_LAST_AT, 0)
        val connectedAt = sp.getLong(KEY_CONNECTED_AT, 0)
        val state = ctx.getString(
            if (connected) R.string.diag_service_connected else R.string.diag_service_disconnected
        )
        return buildString {
            append(state)
            if (connectedAt > 0) append(ctx.getString(R.string.diag_connected_at, t(connectedAt)))
            append("\n")
            append(ctx.getString(R.string.diag_today, received, allowed, created, dropped))
            if (lastAt > 0) {
                append("\n")
                append(ctx.getString(R.string.diag_last, lastApp.ifBlank { ctx.getString(R.string.diag_unknown_app) }, t(lastAt)))
            }
            if (received == 0) {
                append("\n")
                append(ctx.getString(R.string.diag_restart_hint))
            }
        }
    }
}

/** 持久化的去重表：同一个 App、同标题、同一个解析时间只创建一次（7 天内有效）。 */
object SeenStore {
    private const val MAX = 300
    private const val TTL = 7L * 24 * 3600 * 1000
    private fun sp(ctx: Context) = ctx.getSharedPreferences("seen", Context.MODE_PRIVATE)

    @Synchronized
    fun isSeen(ctx: Context, key: String): Boolean {
        if (key.isBlank()) return false
        val sp = sp(ctx)
        val o = JSONObject(sp.getString("keys", "{}"))
        val at = o.optLong(key, 0)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > TTL) {
            o.remove(key)
            sp.edit().putString("keys", o.toString()).apply()
            return false
        }
        return true
    }

    @Synchronized
    fun mark(ctx: Context, key: String) {
        if (key.isBlank()) return
        val sp = sp(ctx)
        val o = JSONObject(sp.getString("keys", "{}"))
        val now = System.currentTimeMillis()
        val keys = o.keys().asSequence().toList()
        keys.filter { now - o.optLong(it) > TTL }.forEach { o.remove(it) }
        while (o.length() >= MAX) {
            val oldest = o.keys().asSequence().minByOrNull { o.optLong(it) } ?: break
            o.remove(oldest)
        }
        o.put(key, now)
        sp.edit().putString("keys", o.toString()).apply()
    }
}
