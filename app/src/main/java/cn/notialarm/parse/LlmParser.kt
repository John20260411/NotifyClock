package cn.notialarm.parse

import cn.notialarm.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 调用 OpenAI 兼容的 /chat/completions 接口（DeepSeek、通义千问、Kimi、智谱等都支持）。
 */
object LlmParser {
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val WEEK = arrayOf("一", "二", "三", "四", "五", "六", "日")

    /** 不是事件返回 null；网络或接口错误抛异常。 */
    fun parse(prefs: Prefs, text: String, now: LocalDateTime = LocalDateTime.now()): TimeParser.Result? {
        val system = """
            你从手机通知里提取需要提醒的日程。当前时间：${now.format(FMT)}，星期${WEEK[now.dayOfWeek.value - 1]}。
            判断通知是否包含一个未来的、有明确日期或时间的事件（会议、考试、上课、作业截止、活动、集合等）。
            只输出一个 JSON 对象，不要输出其他内容：
            {"event": true 或 false, "title": "简短的事件名，不超过20字", "time": "yyyy-MM-dd HH:mm"}
            只有日期没有具体时刻时，time 取当天 09:00。闲聊、广告、已经过去的事件，event 为 false。
        """.trimIndent()

        val body = JSONObject()
            .put("model", prefs.llmModel)
            .put("temperature", 0)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", text.take(1000)))
            )

        val base = prefs.llmBaseUrl.trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${prefs.llmApiKey}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${resp.take(200)}")

            val content = JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val json = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(content)?.value
                ?: throw RuntimeException("模型没有返回 JSON：${content.take(100)}")
            val o = JSONObject(json)
            if (!o.optBoolean("event")) return null
            val time = runCatching { LocalDateTime.parse(o.getString("time").trim(), FMT) }.getOrNull() ?: return null
            if (time.isBefore(now.minusMinutes(1))) return null
            val title = o.optString("title").ifBlank { text.take(20) }
            return TimeParser.Result(time, title, "大模型")
        } finally {
            conn.disconnect()
        }
    }
}
