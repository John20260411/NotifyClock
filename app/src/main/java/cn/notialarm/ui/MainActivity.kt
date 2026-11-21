package cn.notialarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import cn.notialarm.Fmt
import cn.notialarm.Notifier
import cn.notialarm.NotifyListener
import cn.notialarm.Processor
import cn.notialarm.R
import cn.notialarm.Reminders
import cn.notialarm.SourceLauncher
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.Diag
import cn.notialarm.data.Dropped
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.ReminderStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDateTime

class MainActivity : AppCompatActivity() {

    private class Perm(val name: String, val desc: String, val granted: Boolean, val fix: () -> Unit)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var permContainer: LinearLayout
    private lateinit var reminderContainer: LinearLayout
    private lateinit var droppedContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyDropped: TextView
    private lateinit var logText: TextView
    private lateinit var tvDiag: TextView
    private lateinit var btnActive: Button
    private lateinit var btnArchived: Button

    /** false = 看待处理；true = 看归档。 */
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannels(this)

        permContainer = findViewById(R.id.permContainer)
        reminderContainer = findViewById(R.id.reminderContainer)
        droppedContainer = findViewById(R.id.droppedContainer)
        emptyText = findViewById(R.id.emptyText)
        emptyDropped = findViewById(R.id.emptyDropped)
        logText = findViewById(R.id.logText)
        tvDiag = findViewById(R.id.tvDiag)
        btnActive = findViewById(R.id.btnActive)
        btnArchived = findViewById(R.id.btnArchived)

        val input = findViewById<EditText>(R.id.inputText)
        findViewById<Button>(R.id.btnParse).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            Processor.submit(applicationContext, "手动测试", text) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    renderLog()
                    renderReminders()
                }
            }
        }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEdit(null) }
        findViewById<Button>(R.id.btnTestListener).setOnClickListener { testListener() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnClearDropped).setOnClickListener {
            DroppedStore.clear(this)
            renderDropped()
            Toast.makeText(this, "已清空未记录列表", Toast.LENGTH_SHORT).show()
        }
        btnActive.setOnClickListener { showArchived = false; renderReminders() }
        btnArchived.setOnClickListener { showArchived = true; renderReminders() }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getIntExtra(Notifier.EXTRA_EDIT_ID, -1) ?: -1
        if (id > 0) ReminderStore.get(this, id)?.let { showEdit(it) }
    }

    override fun onResume() {
        super.onResume()
        Reminders.onChange = {
            renderReminders()
            renderLog()
            renderDiag()
            renderDropped()
        }
        // 权限开着但服务没连上：自动重连一次
        NotifyListener.onStateChanged = {
            renderPermissions()
            renderDiag()
        }
        if (listenerGranted() && !NotifyListener.connected) reconnectListener(manual = false)
        // 顺手把已经过了时间的提醒归档，列表不再被旧项占满
        Reminders.housekeeping(this)
        renderPermissions()
        renderReminders()
        renderLog()
        renderDiag()
        renderDropped()
    }

    override fun onPause() {
        Reminders.onChange = null
        NotifyListener.onStateChanged = null
        super.onPause()
    }

    private fun perms(): List<Perm> = buildList {
        val pkgUri = Uri.parse("package:$packageName")
        if (!listenerGranted()) {
            add(Perm("通知使用权", "读取微信、QQ 等 App 的通知", false) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        } else {
            add(Perm("通知使用权", if (NotifyListener.connected) "监听服务运行中" else
                "已授权，但监听服务没有连上（覆盖安装后常见）。点“去开启”重连；还不行就在通知使用权里把本 App 关掉再打开",
                NotifyListener.connected) { reconnectListener(manual = true) })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Perm("发送通知", "弹出提醒和闹钟",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Perm("精确闹钟", "让闹钟准时响", AlarmScheduler.canExact(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            })
        }
        if (Build.VERSION.SDK_INT >= 34) {
            add(Perm("全屏弹窗", "锁屏时直接弹出闹钟页面",
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
            })
        }
        add(Perm("显示在其他应用上层（悬浮窗）", "亮屏时弹出确认窗口，没有它只会显示横幅",
            Settings.canDrawOverlays(this@MainActivity)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
        })
        add(Perm("忽略电池优化", "防止后台被系统杀掉",
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
        })
        // 国产系统特有、无法检测的权限：只给入口
        add(Perm("手机厂商权限（请手动确认）",
            "小米：后台弹出界面、锁屏显示、自启动；华为：应用启动管理改为手动并全部允许；OPPO/vivo：允许后台弹出、自启动",
            false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
        })
    }

    private fun listenerGranted() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun reconnectListener(manual: Boolean) {
        NotifyListener.rebind(this)
        handler.postDelayed({
            renderPermissions()
            if (manual && !NotifyListener.connected) {
                Toast.makeText(this, "还是没连上：请在通知使用权里把“通知闹钟”关掉再打开", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }, 2000)
    }

    /** 给自己发一条通知，2 秒内监听服务收到就说明链路通了。 */
    private fun testListener() {
        if (!listenerGranted()) {
            Toast.makeText(this, "请先开启通知使用权", Toast.LENGTH_SHORT).show()
            return
        }
        val sentAt = System.currentTimeMillis()
        Notifier.listenerTest(this)
        handler.postDelayed({
            if (NotifyListener.lastTestAck >= sentAt) {
                Toast.makeText(this, "✅ 监听正常：能收到通知", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "❌ 没收到测试通知，正在尝试重连监听服务…", Toast.LENGTH_LONG).show()
                reconnectListener(manual = true)
            }
            renderPermissions()
            renderDiag()
        }, 2000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissions()
    }

    private fun renderPermissions() {
        permContainer.removeAllViews()
        for (p in perms()) {
            val row = layoutInflater.inflate(R.layout.item_permission, permContainer, false)
            row.findViewById<TextView>(R.id.permName).text = (if (p.granted) "✅ " else "⚠️ ") + p.name
            row.findViewById<TextView>(R.id.permDesc).text = p.desc
            row.findViewById<Button>(R.id.permFix).apply {
                visibility = if (p.granted) View.GONE else View.VISIBLE
                if (p.name.startsWith("手机厂商")) text = "去设置"
                setOnClickListener { runCatching { p.fix() } }
            }
            permContainer.addView(row)
        }
    }

    private fun renderReminders() {
        reminderContainer.removeAllViews()
        val active = ReminderStore.active(this)
        val archived = ReminderStore.archived(this)
        btnActive.text = "待处理 ${active.size}"
        btnArchived.text = "归档 ${archived.size}"
        btnActive.isEnabled = true
        btnArchived.isEnabled = true
        btnActive.alpha = if (!showArchived) 1f else 0.5f
        btnArchived.alpha = if (showArchived) 1f else 0.5f

        val list = if (showArchived) archived else active
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (showArchived) {
            "归档是空的。已过期或你点过“确认不再提醒”的提醒会放在这里。"
        } else {
            "还没有待处理提醒。收到含时间的通知后会自动出现在这里。"
        }

        val now = LocalDateTime.now()
        for (r in list) {
            val v = layoutInflater.inflate(R.layout.item_reminder, reminderContainer, false)
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.time).text = Fmt.dateTime(r.time)
            val meta = buildString {
                if (r.archived) {
                    append("已归档")
                    if (r.confirmed) append(" · 已确认")
                } else {
                    append(Fmt.offsets(r.offsets))
                    val times = AlarmScheduler.plan(this@MainActivity, r)
                    if (times.isNotEmpty()) append(" · ").append(Fmt.ringTimes(r.time, times))
                }
                append("\n来自 ").append(r.source)
                if (r.timeAssumed) append(" · 原通知只写了日期")
            }
            v.findViewById<TextView>(R.id.meta).text = meta
            if (!r.archived && r.time.isBefore(now)) v.alpha = 0.55f

            v.findViewById<Button>(R.id.btnDetail).setOnClickListener { showDetail(r) }
            v.findViewById<Button>(R.id.btnOpen).apply {
                visibility = if (r.pkg.isNotBlank() || r.notifKey.isNotBlank()) View.VISIBLE else View.GONE
                setOnClickListener { openSource(r) }
            }
            v.findViewById<Button>(R.id.btnConfirm).apply {
                visibility = if (r.archived) View.GONE else View.VISIBLE
                setOnClickListener { Reminders.confirm(this@MainActivity, r.id) }
            }
            v.findViewById<Button>(R.id.btnArchive).apply {
                visibility = if (r.archived) View.GONE else View.VISIBLE
                setOnClickListener { Reminders.archive(this@MainActivity, r.id) }
            }
            v.findViewById<Button>(R.id.btnRestore).apply {
                visibility = if (r.archived) View.VISIBLE else View.GONE
                setOnClickListener { Reminders.restore(this@MainActivity, r.id) }
            }
            v.findViewById<Button>(R.id.btnEdit).setOnClickListener { showEdit(r) }
            v.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                Reminders.delete(this, r.id)
            }
            reminderContainer.addView(v)
        }
    }

    /** 弹窗里正文不截断，可滚动、可复制。 */
    private fun showDetail(r: Reminder) {
        val tv = TextView(this).apply {
            text = buildString {
                append(Fmt.dateTime(r.time))
                if (r.timeAssumed) append("（原通知只写了日期，时间按默认估算）")
                append("\n来自 ").append(r.source)
                if (r.archived) append("\n已归档")
                append("\n\n").append(r.text.ifBlank { "（没有正文）" })
            }
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(r.title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNeutralButton("打开原通知") { _, _ -> openSource(r) }
            .create()
        dialog.show()
    }

    private fun openSource(r: Reminder) {
        if (!SourceLauncher.open(this, r)) {
            Toast.makeText(this, "原通知已经消失，或来源 App 已卸载", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderDropped() {
        droppedContainer.removeAllViews()
        val list = DroppedStore.all(this)
        emptyDropped.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (d in list) {
            val v = layoutInflater.inflate(R.layout.item_dropped, droppedContainer, false)
            v.findViewById<TextView>(R.id.dropApp).text = buildString {
                append(d.app.ifBlank { d.pkg })
                if (d.conversation.isNotBlank() && d.conversation != d.app) {
                    append(" · ").append(d.conversation)
                }
            }
            v.findViewById<TextView>(R.id.dropReason).text = "${d.reason} · ${d.count} 次"
            v.findViewById<TextView>(R.id.dropText).text = d.text.ifBlank { "（没有正文）" }

            v.findViewById<Button>(R.id.btnListen).apply {
                visibility = if (d.pkg.isBlank()) View.GONE else View.VISIBLE
                setOnClickListener {
                    val p = Prefs(this@MainActivity)
                    p.monitoredPackages = p.monitoredPackages + d.pkg
                    p.monitorAll = false
                    DroppedStore.remove(this@MainActivity, d)
                    Toast.makeText(this@MainActivity, "已加入监听：${d.app}", Toast.LENGTH_SHORT).show()
                    renderDropped()
                }
            }
            v.findViewById<Button>(R.id.btnBlock).apply {
                text = if (d.conversation.isNotBlank()) "屏蔽此会话" else "屏蔽此应用"
                visibility =
                    if (d.conversation.isNotBlank() || d.pkg.isNotBlank()) View.VISIBLE else View.GONE
                setOnClickListener {
                    val p = Prefs(this@MainActivity)
                    if (d.conversation.isNotBlank()) {
                        p.blackConversations = p.blackConversations + d.conversation
                    } else {
                        p.blackPackages = p.blackPackages + d.pkg
                    }
                    Reminders.archiveBlacklisted(this@MainActivity)
                    DroppedStore.remove(this@MainActivity, d)
                    Toast.makeText(this@MainActivity, "以后不再记录", Toast.LENGTH_SHORT).show()
                    renderDropped()
                }
            }
            v.findViewById<Button>(R.id.btnRetry).setOnClickListener {
                DroppedStore.remove(this, d)
                val incoming = Incoming(
                    d.pkg, d.app, d.conversation, d.title, d.text, "", null,
                    System.currentTimeMillis(),
                )
                Processor.submit(applicationContext, incoming) { _, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        renderReminders()
                        renderDropped()
                    }
                }
            }
            v.findViewById<Button>(R.id.btnDismiss).setOnClickListener {
                DroppedStore.remove(this, d)
                renderDropped()
            }
            droppedContainer.addView(v)
        }
    }

    private fun renderDiag() {
        tvDiag.text = Diag.summary(this, NotifyListener.connected)
    }

    private fun renderLog() {
        val lines = ActivityLog.lines(this)
        logText.text = if (lines.isEmpty()) "还没有处理过通知" else lines.joinToString("\n")
    }

    private fun showEdit(r: Reminder?) {
        val v = layoutInflater.inflate(R.layout.dialog_edit, null)
        val etTitle = v.findViewById<EditText>(R.id.etTitle)
        val etTime = v.findViewById<EditText>(R.id.etTime)
        val etOffsets = v.findViewById<EditText>(R.id.etOffsets)
        val tvSource = v.findViewById<TextView>(R.id.tvSource)
        etTitle.setText(r?.title ?: "")
        etTime.setText((r?.time ?: LocalDateTime.now().plusHours(1).withMinute(0)).format(Fmt.INPUT))
        etOffsets.setText((r?.offsets ?: Prefs(this).defaultOffsets).joinToString(","))
        if (r != null && r.text.isNotBlank()) {
            tvSource.text = "原文（${r.source}）：\n${r.text}"
            tvSource.visibility = View.VISIBLE
            tvSource.setTypeface(null, Typeface.ITALIC)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (r == null) "新建提醒" else "修改提醒")
            .setView(v)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim().ifBlank { "提醒" }
                val time = runCatching { LocalDateTime.parse(etTime.text.toString().trim(), Fmt.INPUT) }.getOrNull()
                val offsets = Prefs.parseOffsets(etOffsets.text.toString())
                when {
                    time == null -> etTime.error = "格式：2026-11-01 15:00"
                    offsets == null -> etOffsets.error = "用逗号分隔的分钟数，如 60,10,0"
                    r == null -> {
                        Reminders.create(this, title, time, "手动添加", "", offsets)
                        dialog.dismiss()
                    }
                    else -> {
                        Reminders.update(this, r.copy(title = title, time = time, offsets = offsets))
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
