package cn.notialarm.data

import android.content.Context
import cn.notialarm.alarm.QuietHours
import java.time.LocalTime

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val packagesConfigured: Boolean get() = sp.contains(KEY_PKGS)

    var monitoredPackages: Set<String>
        get() = sp.getStringSet(KEY_PKGS, null) ?: DEFAULT_PKGS
        set(v) = sp.edit().putStringSet(KEY_PKGS, v).apply()

    /** 默认提前量（分钟），从大到小。 */
    var defaultOffsets: List<Int>
        get() = parseOffsets(sp.getString("offsets", null) ?: "") ?: listOf(60, 10, 0)
        set(v) = sp.edit().putString("offsets", v.joinToString(",")).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    /** 自选铃声的 Uri（系统铃声或本地音乐），空 = 系统默认闹钟铃声。 */
    var soundUri: String
        get() = sp.getString("sound_uri", "")!!
        set(v) = sp.edit().putString("sound_uri", v).apply()

    var soundName: String
        get() = sp.getString("sound_name", "")!!
        set(v) = sp.edit().putString("sound_name", v).apply()

    /** true = 跟随媒体音量；false = 跟随闹钟音量。 */
    var useMediaVolume: Boolean
        get() = sp.getBoolean("media_volume", false)
        set(v) = sp.edit().putBoolean("media_volume", v).apply()

    /** 响铃持续分钟数，0 = 一直响到确认（最长 [MAX_RING_MINUTES] 分钟）。 */
    var ringMinutes: Int
        get() = sp.getInt("ring_minutes", 5)
        set(v) = sp.edit().putInt("ring_minutes", v).apply()

    /** 弹窗里的“延后”选项（分钟）。 */
    var snoozeOptions: List<Int>
        get() = parseOffsets(sp.getString("snooze", null) ?: "")?.filter { it > 0 }?.sorted()
            ?.takeIf { it.isNotEmpty() } ?: listOf(5, 10, 30, 60)
        set(v) = sp.edit().putString("snooze", v.joinToString(",")).apply()

    var quietEnabled: Boolean
        get() = sp.getBoolean("quiet", false)
        set(v) = sp.edit().putBoolean("quiet", v).apply()

    var quietStart: LocalTime
        get() = LocalTime.parse(sp.getString("quiet_start", "23:00"))
        set(v) = sp.edit().putString("quiet_start", v.toString()).apply()

    var quietEnd: LocalTime
        get() = LocalTime.parse(sp.getString("quiet_end", "07:30"))
        set(v) = sp.edit().putString("quiet_end", v.toString()).apply()

    /** 事件本身落在免打扰时段里时，“准时”那次是否照常响。 */
    var quietKeepOnTime: Boolean
        get() = sp.getBoolean("quiet_keep", true)
        set(v) = sp.edit().putBoolean("quiet_keep", v).apply()

    val quietHours: QuietHours?
        get() = if (quietEnabled) QuietHours(quietStart, quietEnd, quietKeepOnTime) else null

    /** rule = 仅本地规则；hybrid = 规则优先，失败再问大模型；llm = 大模型优先，失败用规则。 */
    var parseMode: String
        get() = sp.getString("mode", MODE_HYBRID)!!
        set(v) = sp.edit().putString("mode", v).apply()

    var llmBaseUrl: String
        get() = sp.getString("llm_url", "https://api.deepseek.com")!!
        set(v) = sp.edit().putString("llm_url", v.trim()).apply()

    var llmApiKey: String
        get() = sp.getString("llm_key", "")!!
        set(v) = sp.edit().putString("llm_key", v.trim()).apply()

    var llmModel: String
        get() = sp.getString("llm_model", "deepseek-chat")!!
        set(v) = sp.edit().putString("llm_model", v.trim()).apply()

    val llmReady: Boolean get() = llmApiKey.isNotBlank() && llmBaseUrl.isNotBlank() && llmModel.isNotBlank()

    companion object {
        private const val KEY_PKGS = "pkgs"
        const val MODE_RULE = "rule"
        const val MODE_HYBRID = "hybrid"
        const val MODE_LLM = "llm"
        val DEFAULT_PKGS = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")
        const val MAX_OFFSETS = 9
        const val MAX_RING_MINUTES = 30

        /** "60, 10，0" -> [60, 10, 0]；有非法项返回 null。 */
        fun parseOffsets(s: String): List<Int>? {
            val parts = s.split(Regex("[,，、\\s]+")).filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 || it > 7 * 24 * 60 }) return null
            return nums.distinct().sortedDescending().take(MAX_OFFSETS)
        }
    }
}
