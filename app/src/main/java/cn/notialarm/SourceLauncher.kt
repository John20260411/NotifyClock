package cn.notialarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Base64
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Reminder
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 保存“这条提醒是从哪条通知来的”，并负责一键跳回去。
 * PendingIntent 先放内存，再序列化到 SharedPreferences，进程被杀后仍可尝试跳转。
 */
object SourceLauncher {
    private const val PREFS = "jump_pi"
    private const val KEY_MAP = "map"
    private const val MAX = 40
    private val memory = ConcurrentHashMap<String, PendingIntent>()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 通知的 key 可能很长，持久化时只存摘要。 */
    fun keyOf(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        } catch (t: Throwable) {
            raw.take(40)
        }
    }

    fun remember(ctx: Context, notifKey: String, pi: PendingIntent?) {
        if (notifKey.isBlank() || pi == null) return
        val k = keyOf(notifKey)
        memory[k] = pi
        val encoded = encode(pi) ?: return
        val o = JSONObject(sp(ctx).getString(KEY_MAP, "{}"))
        o.put(k, encoded)
        while (o.length() > MAX) {
            val first = o.keys().asSequence().firstOrNull() ?: break
            o.remove(first)
        }
        sp(ctx).edit().putString(KEY_MAP, o.toString()).apply()
    }

    private fun persisted(ctx: Context, key: String): PendingIntent? {
        val o = JSONObject(sp(ctx).getString(KEY_MAP, "{}"))
        val s = o.optString(key, "")
        if (s.isBlank()) return null
        return decode(s)
    }

    private fun encode(pi: PendingIntent): String? = try {
        val parcel = Parcel.obtain()
        pi.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }

    private fun decode(s: String): PendingIntent? {
        val parcel = Parcel.obtain()
        return try {
            val bytes = Base64.decode(s, Base64.NO_WRAP)
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            PendingIntent.CREATOR.createFromParcel(parcel)
        } catch (t: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * 依次尝试：内存里的 PendingIntent → 持久化的 PendingIntent → 当前活动通知的 contentIntent
     * → 直接打开来源 App。全部失败返回 false。
     */
    fun open(ctx: Context, r: Reminder): Boolean {
        val k = keyOf(r.notifKey)
        val candidates = listOfNotNull(
            memory[k],
            persisted(ctx, k),
            NotifyListener.activeByKey(r.notifKey)?.notification?.contentIntent,
        )
        for (pi in candidates) {
            try {
                pi.send()
                ActivityLog.add(ctx, "已跳回原通知 · ${r.source}")
                return true
            } catch (t: Throwable) {
                // 通知已被清除时 PendingIntent 会失效，继续尝试下一种方式。
            }
        }
        if (r.pkg.isNotBlank()) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(r.pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    ctx.startActivity(launch)
                    ActivityLog.add(ctx, "原通知已消失，已打开 ${r.source}")
                    true
                } catch (t: Throwable) {
                    false
                }
            }
        }
        ActivityLog.add(ctx, "无法跳转：原通知已消失，或来源 App 已卸载")
        return false
    }
}
