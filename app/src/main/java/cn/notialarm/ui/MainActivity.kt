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
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.data.ActivityLog
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
    private lateinit var emptyText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannels(this)

        permContainer = findViewById(R.id.permContainer)
        reminderContainer = findViewById(R.id.reminderContainer)
        emptyText = findViewById(R.id.emptyText)
        logText = findViewById(R.id.logText)

        val input = findViewById<EditText>(R.id.inputText)
        findViewById<Button>(R.id.btnParse).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) return@setOnClickListener
            Processor.submit(applicationContext, "手动测试", text) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    renderLog()
                }
            }
        }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEdit(null) }
        findViewById<Button>(R.id.btnTestListener).setOnClickListener { testListener() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        Reminders.onChange = { renderReminders(); renderLog() }
        // 权限开着但服务没连上：自动重连一次
        if (listenerGranted() && !NotifyListener.connected) reconnectListener(manual = false)
        renderPermissions()
        renderReminders()
        renderLog()
    }

    override fun onPause() {
        Reminders.onChange = null
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
        val now = LocalDateTime.now()
        val list = ReminderStore.all(this)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (r in list) {
            val v = layoutInflater.inflate(R.layout.item_reminder, reminderContainer, false)
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.time).text = Fmt.dateTime(r.time)
            v.findViewById<TextView>(R.id.meta).text =
                "${Fmt.offsets(r.offsets)}\n${Fmt.ringTimes(r.time, AlarmScheduler.plan(this, r))}\n来自 ${r.source}"
            if (r.time.isBefore(now)) v.alpha = 0.45f
            v.findViewById<Button>(R.id.btnEdit).setOnClickListener { showEdit(r) }
            v.findViewById<Button>(R.id.btnDelete).setOnClickListener { Reminders.delete(this, r.id) }
            reminderContainer.addView(v)
        }
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
}
