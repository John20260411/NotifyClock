package cn.notialarm.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cn.notialarm.Fmt
import cn.notialarm.R
import cn.notialarm.Reminders
import cn.notialarm.alarm.AlarmService
import cn.notialarm.data.Prefs
import cn.notialarm.parse.LlmParser
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.LocalTime

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val selected = mutableSetOf<String>()

    // 铃声和免打扰时间在点“保存”之前先放这里
    private var soundUri = ""
    private var soundName = ""
    private var quietStart = LocalTime.of(23, 0)
    private var quietEnd = LocalTime.of(7, 30)
    private var preview: MediaPlayer? = null

    private lateinit var tvSound: TextView
    private lateinit var btnPreview: Button
    private lateinit var rgVolume: RadioGroup

    private val pickRingtone = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        if (uri == null || uri == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) {
            setSound("", "")
        } else {
            setSound(uri.toString(), RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "系统铃声")
        }
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // 持久授权，重启手机后闹钟服务仍能读这个文件
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "本地音乐"
        setSound(uri.toString(), name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "设置"
        prefs = Prefs(this)

        val etOffsets = findViewById<EditText>(R.id.etOffsets)
        val swSound = findViewById<MaterialSwitch>(R.id.swSound)
        val swVibrate = findViewById<MaterialSwitch>(R.id.swVibrate)
        val etRing = findViewById<EditText>(R.id.etRingMinutes)
        val etSnooze = findViewById<EditText>(R.id.etSnooze)
        val swQuiet = findViewById<MaterialSwitch>(R.id.swQuiet)
        val swQuietKeep = findViewById<MaterialSwitch>(R.id.swQuietKeep)
        val btnQuietStart = findViewById<Button>(R.id.btnQuietStart)
        val btnQuietEnd = findViewById<Button>(R.id.btnQuietEnd)
        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        val etUrl = findViewById<EditText>(R.id.etUrl)
        val etKey = findViewById<EditText>(R.id.etKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        tvSound = findViewById(R.id.tvSound)
        btnPreview = findViewById(R.id.btnPreview)
        rgVolume = findViewById(R.id.rgVolume)

        etOffsets.setText(prefs.defaultOffsets.joinToString(","))
        swSound.isChecked = prefs.sound
        swVibrate.isChecked = prefs.vibrate
        setSound(prefs.soundUri, prefs.soundName)
        rgVolume.check(if (prefs.useMediaVolume) R.id.rbMediaVolume else R.id.rbAlarmVolume)
        etRing.setText(prefs.ringMinutes.toString())
        etSnooze.setText(prefs.snoozeOptions.joinToString(","))
        swQuiet.isChecked = prefs.quietEnabled
        swQuietKeep.isChecked = prefs.quietKeepOnTime
        quietStart = prefs.quietStart
        quietEnd = prefs.quietEnd
        btnQuietStart.text = "开始 $quietStart"
        btnQuietEnd.text = "结束 $quietEnd"
        rgMode.check(
            when (prefs.parseMode) {
                Prefs.MODE_RULE -> R.id.rbRule
                Prefs.MODE_LLM -> R.id.rbLlm
                else -> R.id.rbHybrid
            }
        )
        etUrl.setText(prefs.llmBaseUrl)
        etKey.setText(prefs.llmApiKey)
        etModel.setText(prefs.llmModel)

        findViewById<Button>(R.id.btnPickRingtone).setOnClickListener {
            stopPreview()
            pickRingtone.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声")
                    .putExtra(
                        RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    )
            )
        }
        findViewById<Button>(R.id.btnPickMusic).setOnClickListener {
            stopPreview()
            pickMusic.launch(arrayOf("audio/*"))
        }
        btnPreview.setOnClickListener { if (preview == null) startPreview() else stopPreview() }
        rgVolume.setOnCheckedChangeListener { _, _ -> if (preview != null) { stopPreview(); startPreview() } }

        btnQuietStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                quietStart = LocalTime.of(h, m)
                btnQuietStart.text = "开始 $quietStart"
            }, quietStart.hour, quietStart.minute, true).show()
        }
        btnQuietEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                quietEnd = LocalTime.of(h, m)
                btnQuietEnd.text = "结束 $quietEnd"
            }, quietEnd.hour, quietEnd.minute, true).show()
        }

        findViewById<Button>(R.id.btnTestLlm).setOnClickListener {
            prefs.llmBaseUrl = etUrl.text.toString()
            prefs.llmApiKey = etKey.text.toString()
            prefs.llmModel = etModel.text.toString()
            if (!prefs.llmReady) {
                Toast.makeText(this, "请先填写接口地址、API Key 和模型名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "正在请求…", Toast.LENGTH_SHORT).show()
            Thread {
                val msg = runCatching { LlmParser.parse(prefs, "明天下午3点在正心楼开组会，别迟到") }
                    .fold(
                        { r -> if (r == null) "接口正常，但模型认为不是事件" else "成功：${Fmt.dateTime(r.time)} ${r.title}" },
                        { e -> "失败：${e.message}" },
                    )
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val offsets = Prefs.parseOffsets(etOffsets.text.toString())
            if (offsets == null) {
                etOffsets.error = "用逗号分隔的分钟数，如 60,10,0（最多 ${Prefs.MAX_OFFSETS} 个）"
                return@setOnClickListener
            }
            val ring = etRing.text.toString().trim().toIntOrNull()
            if (ring == null || ring !in 0..Prefs.MAX_RING_MINUTES) {
                etRing.error = "0–${Prefs.MAX_RING_MINUTES} 分钟，0 = 一直响到确认"
                return@setOnClickListener
            }
            val snooze = Prefs.parseOffsets(etSnooze.text.toString())?.filter { it > 0 }
            if (snooze.isNullOrEmpty()) {
                etSnooze.error = "用逗号分隔的分钟数，如 5,10,30,60"
                return@setOnClickListener
            }
            if (swQuiet.isChecked && quietStart == quietEnd) {
                Toast.makeText(this, "免打扰的开始和结束时间不能相同", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.defaultOffsets = offsets
            prefs.sound = swSound.isChecked
            prefs.vibrate = swVibrate.isChecked
            prefs.soundUri = soundUri
            prefs.soundName = soundName
            prefs.useMediaVolume = rgVolume.checkedRadioButtonId == R.id.rbMediaVolume
            prefs.ringMinutes = ring
            prefs.snoozeOptions = snooze
            prefs.quietEnabled = swQuiet.isChecked
            prefs.quietStart = quietStart
            prefs.quietEnd = quietEnd
            prefs.quietKeepOnTime = swQuietKeep.isChecked
            prefs.parseMode = when (rgMode.checkedRadioButtonId) {
                R.id.rbRule -> Prefs.MODE_RULE
                R.id.rbLlm -> Prefs.MODE_LLM
                else -> Prefs.MODE_HYBRID
            }
            prefs.llmBaseUrl = etUrl.text.toString()
            prefs.llmApiKey = etKey.text.toString()
            prefs.llmModel = etModel.text.toString()
            prefs.monitoredPackages = selected.toSet()
            // 免打扰改了会影响已有提醒的响铃时刻
            Reminders.rescheduleAll(this)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        loadApps()
    }

    private fun setSound(uri: String, name: String) {
        soundUri = uri
        soundName = name
        tvSound.text = "当前铃声：" + if (uri.isBlank()) "系统默认闹钟铃声" else name
        if (preview != null) { stopPreview(); startPreview() }
    }

    private fun startPreview() {
        val media = rgVolume.checkedRadioButtonId == R.id.rbMediaVolume
        val attrs = AudioAttributes.Builder()
            .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM)
            .build()
        val uri = soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        preview = runCatching { AlarmService.play(this, uri, attrs) }
            .onFailure { Toast.makeText(this, "无法播放：${it.message}", Toast.LENGTH_SHORT).show() }
            .getOrNull()
        btnPreview.text = if (preview != null) "停止" else "试听"
    }

    private fun stopPreview() {
        preview?.runCatching { stop(); release() }
        preview = null
        btnPreview.text = "试听"
    }

    override fun onPause() {
        stopPreview()
        super.onPause()
    }

    /** 列出所有有桌面图标的 App，已勾选的排在前面。 */
    private fun loadApps() {
        val container = findViewById<LinearLayout>(R.id.appsContainer)
        val status = findViewById<TextView>(R.id.appsStatus)
        selected.clear()
        selected += prefs.monitoredPackages
        val firstTime = !prefs.packagesConfigured
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filter { it.first != packageName }
            if (firstTime) {
                // 第一次打开：顺手勾上名字像哈工大的 App
                apps.filter { (_, label) -> listOf("哈工大", "工大", "HIT", "hit").any { label.contains(it) } }
                    .forEach { selected += it.first }
            }
            val sorted = apps.sortedWith(compareBy({ it.first !in selected }, { it.second }))
            runOnUiThread {
                status.text = "勾选要监听的 App（共 ${sorted.size} 个）"
                for ((pkg, label) in sorted) {
                    container.addView(CheckBox(this).apply {
                        text = "$label\n$pkg"
                        textSize = 14f
                        isChecked = pkg in selected
                        setOnCheckedChangeListener { _, on -> if (on) selected += pkg else selected -= pkg }
                    })
                }
            }
        }.start()
    }
}
