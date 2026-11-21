package cn.notialarm.data

import android.content.Context
import cn.notialarm.alarm.QuietHours
import java.time.LocalTime

/** 命中的黑名单规则。 */
data class BlackHit(val kind: String, val value: String)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val packagesConfigured: Boolean get() = sp.contains(KEY_PKGS)

    var monitoredPackages: Set<String>
        get() = sp.getStringSet(KEY_PKGS, null) ?: DEFAULT_PKGS
        set(v) = sp.edit().putStringSet(KEY_PKGS, v).apply()

    /** true = 所有 App 的通知都进入解析，不再只限于勾选的列表。 */
    var monitorAll: Boolean
        get() = sp.getBoolean("monitor_all", false)
        set(v) = sp.edit().putBoolean("monitor_all", v).apply()

    /** 按包名屏蔽整个 App。 */
    var blackPackages: Set<String>
        get() = sp.getStringSet(KEY_BLACK_PKGS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BLACK_PKGS, normalizeSet(v)).apply()

    /** 按群聊/联系人名屏蔽。 */
    var blackConversations: Set<String>
        get() = sp.getStringSet(KEY_BLACK_CONVERSATIONS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BLACK_CONVERSATIONS, normalizeSet(v)).apply()

    /** 按关键词屏蔽（命中正文或群名）。 */
    var blackKeywords: Set<String>
        get() = sp.getStringSet(KEY_BLACK_KEYWORDS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BLACK_KEYWORDS, normalizeSet(v)).apply()

    /** 最近创建的提醒 id，汇总通知上的“撤销”用。 */
    var lastCreatedId: Int
        get() = sp.getInt("last_created_id", -1)
        set(v) = sp.edit().putInt("last_created_id", v).apply()

    /**
     * 这条通知是否命中黑名单。优先级：关键词 > 会话 > 应用。
     * 会话和应用按忽略大小写比较；关键词是包含匹配。
     */
    fun blacklistHit(pkg: String, conversation: String, text: String): BlackHit? {
        val body = text.lowercase()
        val conv = conversation.trim().lowercase()
        blackKeywords.firstOrNull { it.isNotBlank() && (body.contains(it) || conv.contains(it)) }
            ?.let { return BlackHit("关键词", it) }
        if (conv.isNotBlank()) {
            blackConversations.firstOrNull { it == conv }
                ?.let { return BlackHit("会话", conversation.trim()) }
        }
        if (pkg.isNotBlank() && pkg.lowercase() in blackPackages) {
            return BlackHit("应用", pkg)
        }
        return null
    }

    private fun normalizeSet(v: Set<String>): Set<String> =
        v.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

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
        private const val KEY_BLACK_PKGS = "black_pkgs"
        private const val KEY_BLACK_CONVERSATIONS = "black_conversations"
        private const val KEY_BLACK_KEYWORDS = "black_keywords"
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
