package cn.notialarm.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import cn.notialarm.Fmt
import cn.notialarm.NotiAlarmApp
import cn.notialarm.Notifier
import cn.notialarm.NotifyListener
import cn.notialarm.R
import cn.notialarm.Reminders
import cn.notialarm.alarm.AlarmService
import cn.notialarm.data.AttemptLog
import cn.notialarm.data.Diag
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Prefs
import cn.notialarm.parse.LlmParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.LocalTime

class SettingsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private val selected = mutableSetOf<String>()
    private lateinit var permContainer: LinearLayout
    private lateinit var tvDiag: TextView

    // 铃声和免打扰时间在点“保存”之前先放这里
    private var soundUri = ""
    private var soundName = ""
    private var quietStart = LocalTime.of(23, 0)
    private var quietEnd = LocalTime.of(7, 30)
    private var preview: MediaPlayer? = null

    private lateinit var tvSound: TextView
    private lateinit var btnPreview: Button
    private lateinit var rgVolume: RadioGroup

    private lateinit var etOffsets: EditText
    private lateinit var swSound: MaterialSwitch
    private lateinit var swVibrate: MaterialSwitch
    private lateinit var etRing: EditText
    private lateinit var etSnooze: EditText
    private lateinit var swQuiet: MaterialSwitch
    private lateinit var swQuietKeep: MaterialSwitch
    private lateinit var rgMode: RadioGroup
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var swMonitorAll: MaterialSwitch
    private lateinit var rgLanguage: RadioGroup
    private lateinit var btnQuietStart: Button
    private lateinit var btnQuietEnd: Button

    /** 初始化期间不保存，避免把默认值/半成品写回去。 */
    private var loading = true
    private val saveRunnable = Runnable { saveAll(false) }

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
            setSound(uri.toString(), RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: getString(R.string.ringtone_system))
        }
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // 持久授权，重启手机后闹钟服务仍能读这个文件
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: getString(R.string.ringtone_local)
        setSound(uri.toString(), name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.action_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.subtitle = getString(R.string.autosave_hint)
        prefs = Prefs(this)

        permContainer = findViewById(R.id.permContainer)
        tvDiag = findViewById(R.id.tvDiag)
        findViewById<Button>(R.id.btnTestListener).setOnClickListener { testListener() }
        findViewById<Button>(R.id.btnAttempts).setOnClickListener { showAttempts() }
        Permissions.render(this, permContainer)
        renderDiag()

        etOffsets = findViewById(R.id.etOffsets)
        swSound = findViewById(R.id.swSound)
        swVibrate = findViewById(R.id.swVibrate)
        etRing = findViewById(R.id.etRingMinutes)
        etSnooze = findViewById(R.id.etSnooze)
        swQuiet = findViewById(R.id.swQuiet)
        swQuietKeep = findViewById(R.id.swQuietKeep)
        btnQuietStart = findViewById(R.id.btnQuietStart)
        btnQuietEnd = findViewById(R.id.btnQuietEnd)
        rgMode = findViewById(R.id.rgMode)
        etUrl = findViewById(R.id.etUrl)
        etKey = findViewById(R.id.etKey)
        etModel = findViewById(R.id.etModel)
        swMonitorAll = findViewById(R.id.swMonitorAll)
        rgLanguage = findViewById(R.id.rgLanguage)
        val spBlackType = findViewById<Spinner>(R.id.spBlackType)
        val etBlackValue = findViewById<EditText>(R.id.etBlackValue)
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
        btnQuietStart.text = getString(R.string.quiet_start_fmt, quietStart.toString())
        btnQuietEnd.text = getString(R.string.quiet_end_fmt, quietEnd.toString())
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
        swMonitorAll.isChecked = prefs.monitorAll

        rgLanguage.check(
            when (prefs.language) {
                "zh" -> R.id.rbLangZh
                "en" -> R.id.rbLangEn
                else -> R.id.rbLangSystem
            }
        )
        rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val tag = when (checkedId) {
                R.id.rbLangZh -> "zh"
                R.id.rbLangEn -> "en"
                else -> ""
            }
            if (tag == prefs.language) return@setOnCheckedChangeListener
            prefs.language = tag
            NotiAlarmApp.applyLanguage(tag)
            recreate()
        }

        // 所有设置项都即时生效：变更后 400ms 合并写一次，离开页面时再强制落盘一次。
        etOffsets.doAfterTextChanged { scheduleSave() }
        etRing.doAfterTextChanged { scheduleSave() }
        etSnooze.doAfterTextChanged { scheduleSave() }
        etUrl.doAfterTextChanged { scheduleSave() }
        etKey.doAfterTextChanged { scheduleSave() }
        etModel.doAfterTextChanged { scheduleSave() }
        swSound.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        swVibrate.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        swQuiet.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        swQuietKeep.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        swMonitorAll.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        rgMode.setOnCheckedChangeListener { _, _ -> scheduleSave() }
        spBlackType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.black_type_conversation),
                getString(R.string.black_type_keyword),
                getString(R.string.black_type_package),
            ),
        )
        findViewById<Button>(R.id.btnAddBlack).setOnClickListener {
            val value = etBlackValue.text.toString().trim()
            if (value.isEmpty()) {
                etBlackValue.error = getString(R.string.hint_black_value)
                return@setOnClickListener
            }
            when (spBlackType.selectedItemPosition) {
                0 -> prefs.blackConversations = prefs.blackConversations + value
                1 -> prefs.blackKeywords = prefs.blackKeywords + value
                else -> prefs.blackPackages = prefs.blackPackages + value
            }
            etBlackValue.setText("")
            val archived = Reminders.archiveBlacklisted(this)
            renderBlacklist()
            Toast.makeText(
                this,
                if (archived > 0) getString(R.string.toast_black_added_archived, archived)
                else getString(R.string.toast_black_added),
                Toast.LENGTH_SHORT,
            ).show()
        }
        etBlackValue.setOnEditorActionListener { _, _, _ ->
            findViewById<Button>(R.id.btnAddBlack).performClick()
            true
        }
        renderBlacklist()

        findViewById<Button>(R.id.btnPickRingtone).setOnClickListener {
            stopPreview()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.change_ringtone)
                .setItems(
                    arrayOf(getString(R.string.ringtone_system), getString(R.string.ringtone_local))
                ) { _, which ->
                    if (which == 0) {
                        pickRingtone.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.pick_alarm_sound))
                                .putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
                                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                                )
                        )
                    } else {
                        pickMusic.launch(arrayOf("audio/*"))
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        btnPreview.setOnClickListener { if (preview == null) startPreview() else stopPreview() }
        rgVolume.setOnCheckedChangeListener { _, _ ->
            scheduleSave()
            if (preview != null) { stopPreview(); startPreview() }
        }

        btnQuietStart.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                quietStart = LocalTime.of(h, m)
                btnQuietStart.text = getString(R.string.quiet_start_fmt, quietStart.toString())
                scheduleSave()
            }, quietStart.hour, quietStart.minute, true).show()
        }
        btnQuietEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                quietEnd = LocalTime.of(h, m)
                btnQuietEnd.text = getString(R.string.quiet_end_fmt, quietEnd.toString())
                scheduleSave()
            }, quietEnd.hour, quietEnd.minute, true).show()
        }

        findViewById<Button>(R.id.btnTestLlm).setOnClickListener {
            prefs.llmBaseUrl = etUrl.text.toString()
            prefs.llmApiKey = etKey.text.toString()
            prefs.llmModel = etModel.text.toString()
            if (!prefs.llmReady) {
                Toast.makeText(this, R.string.llm_need_config, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.llm_requesting, Toast.LENGTH_SHORT).show()
            Thread {
                val msg = runCatching { LlmParser.parse(prefs, "明天下午3点在正心楼开组会，别迟到") }
                    .fold(
                        { r -> if (r == null) getString(R.string.llm_not_event) else getString(R.string.llm_ok, Fmt.dateTime(r.time), r.title) },
                        { e -> getString(R.string.llm_failed, e.message.orEmpty()) },
                    )
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

        loadApps()
        loading = false
    }

    /** 合并短时间内的连续修改，避免每敲一个字都写盘/重排闹钟。 */
    private fun scheduleSave() {
        if (loading) return
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    /**
     * 自动保存。无效输入不覆盖旧值；只有闹钟相关设置变化时才重排。
     * showErrors = true 用于离开页面时，对仍然无效的输入给出提示。
     */
    private fun saveAll(showErrors: Boolean) {
        if (loading) return

        val offsets = Prefs.parseOffsets(etOffsets.text.toString())
        if (offsets == null) {
            if (showErrors) etOffsets.error = getString(R.string.error_offsets, Prefs.MAX_OFFSETS)
        } else {
            etOffsets.error = null
        }

        val ring = etRing.text.toString().trim().toIntOrNull()
        val ringValid = ring != null && ring in 0..Prefs.MAX_RING_MINUTES
        if (!ringValid && showErrors) etRing.error = getString(R.string.error_ring, Prefs.MAX_RING_MINUTES)
        if (ringValid) etRing.error = null

        val snooze = Prefs.parseOffsets(etSnooze.text.toString())?.filter { it > 0 }
        val snoozeValid = !snooze.isNullOrEmpty()
        if (!snoozeValid && showErrors) etSnooze.error = getString(R.string.error_snooze)
        if (snoozeValid) etSnooze.error = null

        // 开始和结束相同表示没有免打扰时段，QuietHours.window 会直接返回 null。
        val oldAlarm = listOf(
            prefs.defaultOffsets, prefs.sound, prefs.vibrate, prefs.soundUri,
            prefs.useMediaVolume, prefs.ringMinutes, prefs.snoozeOptions,
            prefs.quietEnabled, prefs.quietStart, prefs.quietEnd, prefs.quietKeepOnTime,
        )

        if (offsets != null) prefs.defaultOffsets = offsets
        prefs.sound = swSound.isChecked
        prefs.vibrate = swVibrate.isChecked
        prefs.soundUri = soundUri
        prefs.soundName = soundName
        prefs.useMediaVolume = rgVolume.checkedRadioButtonId == R.id.rbMediaVolume
        if (ringValid) prefs.ringMinutes = ring!!
        if (snoozeValid) prefs.snoozeOptions = snooze!!
        prefs.quietEnabled = swQuiet.isChecked
        prefs.quietStart = quietStart
        prefs.quietEnd = quietEnd
        prefs.quietKeepOnTime = swQuietKeep.isChecked
        prefs.parseMode = when (rgMode.checkedRadioButtonId) {
            R.id.rbRule -> Prefs.MODE_RULE
            R.id.rbLlm -> Prefs.MODE_LLM
            else -> Prefs.MODE_HYBRID
        }
        prefs.llmBaseUrl = etUrl.text.toString().trim()
        prefs.llmApiKey = etKey.text.toString().trim()
        prefs.llmModel = etModel.text.toString().trim()
        prefs.monitorAll = swMonitorAll.isChecked
        prefs.monitoredPackages = selected.toSet()
        DroppedStore.retainPackages(this, selected.toSet())

        val newAlarm = listOf(
            prefs.defaultOffsets, prefs.sound, prefs.vibrate, prefs.soundUri,
            prefs.useMediaVolume, prefs.ringMinutes, prefs.snoozeOptions,
            prefs.quietEnabled, prefs.quietStart, prefs.quietEnd, prefs.quietKeepOnTime,
        )
        if (oldAlarm != newAlarm) Reminders.rescheduleAll(this)

        supportActionBar?.subtitle = getString(R.string.toast_saved_auto, LocalTime.now().withNano(0).toString())
        handler.removeCallbacks(resetSubtitle)
        handler.postDelayed(resetSubtitle, 2000)
    }

    private val resetSubtitle = Runnable {
        supportActionBar?.subtitle = getString(R.string.autosave_hint)
    }

    override fun onResume() {
        super.onResume()
        NotifyListener.onStateChanged = {
            Permissions.render(this, permContainer)
            renderDiag()
        }
        if (Permissions.listenerGranted(this) && !NotifyListener.connected) NotifyListener.rebind(this)
        Permissions.render(this, permContainer)
        renderDiag()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Permissions.render(this, permContainer)
    }

    private fun renderDiag() {
        tvDiag.text = Diag.summary(this, NotifyListener.connected)
    }

    /** 只展示本地追踪元数据，不含聊天正文和密钥。 */
    private fun showAttempts() {
        val body = AttemptLog.summary(this, 60)
        val tv = TextView(this).apply {
            text = body
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.attempts_title)
            .setView(scroll)
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostic_clip), body))
                Toast.makeText(this@SettingsActivity, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 给自己发一条通知，2 秒内监听服务收到就说明链路通了。 */
    private fun testListener() {
        if (!Permissions.listenerGranted(this)) {
            Toast.makeText(this, R.string.toast_listener_need, Toast.LENGTH_SHORT).show()
            return
        }
        val sentAt = System.currentTimeMillis()
        Notifier.listenerTest(this)
        handler.postDelayed({
            if (NotifyListener.lastTestAck >= sentAt) {
                Toast.makeText(this, R.string.toast_listener_ok, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.toast_listener_fail, Toast.LENGTH_LONG).show()
                NotifyListener.rebind(this)
            }
            Permissions.render(this, permContainer)
            renderDiag()
        }, 2000)
    }

    private fun setSound(uri: String, name: String) {
        soundUri = uri
        soundName = name
        tvSound.text = getString(R.string.now_ringtone, if (uri.isBlank()) getString(R.string.default_alarm_sound) else name)
        if (preview != null) { stopPreview(); startPreview() }
        scheduleSave()
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
            .onFailure { Toast.makeText(this, getString(R.string.toast_play_failed, it.message.orEmpty()), Toast.LENGTH_SHORT).show() }
            .getOrNull()
        btnPreview.text = getString(if (preview != null) R.string.stop_preview else R.string.preview)
    }

    private fun stopPreview() {
        preview?.runCatching { stop(); release() }
        preview = null
        btnPreview.text = getString(R.string.preview)
    }

    override fun onPause() {
        handler.removeCallbacks(saveRunnable)
        saveAll(true)
        NotifyListener.onStateChanged = null
        stopPreview()
        super.onPause()
    }

    private fun renderBlacklist() {
        val container = findViewById<LinearLayout>(R.id.blackContainer)
        val status = findViewById<TextView>(R.id.blackStatus)
        container.removeAllViews()
        val rows = mutableListOf<Triple<String, String, String>>()
        prefs.blackConversations.sorted().forEach { rows += Triple(getString(R.string.black_kind_conversation), "conversation", it) }
        prefs.blackKeywords.sorted().forEach { rows += Triple(getString(R.string.black_kind_keyword), "keyword", it) }
        prefs.blackPackages.sorted().forEach { rows += Triple(getString(R.string.black_kind_pkg), "pkg", it) }
        status.text =
            if (rows.isEmpty()) getString(R.string.blacklist_empty)
            else getString(R.string.blacklist_summary, rows.size)
        for ((label, kind, value) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = "$label：$value"
                textSize = 13f
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = Button(this).apply {
                text = getString(R.string.remove)
                textSize = 12f
                minWidth = 0
                setOnClickListener {
                    when (kind) {
                        "conversation" -> prefs.blackConversations = prefs.blackConversations - value
                        "keyword" -> prefs.blackKeywords = prefs.blackKeywords - value
                        else -> prefs.blackPackages = prefs.blackPackages - value
                    }
                    renderBlacklist()
                }
            }
            row.addView(tv)
            row.addView(remove)
            container.addView(row)
        }
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
                status.text = if (prefs.monitorAll) {
                    getString(R.string.apps_monitor_all)
                } else {
                    getString(R.string.apps_count, sorted.size)
                }
                for ((pkg, label) in sorted) {
                    container.addView(CheckBox(this).apply {
                        text = "$label\n$pkg"
                        textSize = 14f
                        isChecked = pkg in selected
                        setOnCheckedChangeListener { _, on ->
                            if (on) selected += pkg else selected -= pkg
                            scheduleSave()
                        }
                    })
                }
                if (firstTime) scheduleSave()
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
