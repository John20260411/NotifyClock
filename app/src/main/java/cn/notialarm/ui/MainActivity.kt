package cn.notialarm.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cn.notialarm.Fmt
import cn.notialarm.Notifier
import cn.notialarm.NotifyListener
import cn.notialarm.Processor
import cn.notialarm.R
import cn.notialarm.Reminders
import cn.notialarm.SourceLauncher
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.ReminderStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDateTime
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var reminderContainer: LinearLayout
    private lateinit var droppedContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyDropped: TextView
    private lateinit var logText: TextView
    private lateinit var tvPermWarning: TextView
    private lateinit var tvListHint: TextView
    private lateinit var btnActive: Button
    private lateinit var btnArchived: Button

    /** false = 看待处理；true = 看归档。 */
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannels(this)

        reminderContainer = findViewById(R.id.reminderContainer)
        droppedContainer = findViewById(R.id.droppedContainer)
        emptyText = findViewById(R.id.emptyText)
        emptyDropped = findViewById(R.id.emptyDropped)
        logText = findViewById(R.id.logText)
        tvPermWarning = findViewById(R.id.tvPermWarning)
        tvListHint = findViewById(R.id.tvListHint)
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
        tvPermWarning.setOnClickListener {
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        Reminders.onChange = {
            renderReminders()
            renderLog()
            renderDropped()
            renderPermWarning()
        }
        NotifyListener.onStateChanged = {
            renderPermWarning()
        }
        // 权限开着但服务没连上：自动重连一次
        if (Permissions.listenerGranted(this) && !NotifyListener.connected) NotifyListener.rebind(this)
        // 顺手把已经过了时间的提醒归档，列表不再被旧项占满
        Reminders.housekeeping(this)
        renderPermWarning()
        renderReminders()
        renderLog()
        renderDropped()
    }

    override fun onPause() {
        Reminders.onChange = null
        NotifyListener.onStateChanged = null
        super.onPause()
    }

    /** 主页只提示一行；完整权限清单在右上角齿轮里的设置页。 */
    private fun renderPermWarning() {
        val granted = Permissions.listenerGranted(this)
        val ok = granted && NotifyListener.connected
        tvPermWarning.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) return
        tvPermWarning.text = if (!granted) {
            "⚠ 通知使用权未开启，点这里去设置"
        } else {
            "⚠ 监听服务未连接，点这里检查或重连"
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
        tvListHint.text = if (showArchived) {
            "点按查看全文 · 左滑恢复 · 长按删除"
        } else {
            "点按查看全文 · 左滑归档 · 长按修改或删除"
        }
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (showArchived) {
            "归档是空的。已过期或你点过“彻底关闭”的提醒会放在这里。"
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

            // 点按看全文、左滑归档/恢复、长按修改或删除，列表不再堆一排按钮
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var swiped = false
            var longFired = false
            val longPress = Runnable {
                longFired = true
                showItemMenu(r)
            }
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        downAt = System.currentTimeMillis()
                        swiped = false
                        longFired = false
                        view.postDelayed(longPress, 500)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > dp(12) || abs(dy) > dp(12)) view.removeCallbacks(longPress)
                        if (dx < -dp(70) && abs(dx) > abs(dy) * 1.5f) swiped = true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.removeCallbacks(longPress)
                        if (swiped) {
                            if (r.archived) {
                                Reminders.restore(this@MainActivity, r.id)
                                Toast.makeText(this@MainActivity, "已恢复到待处理", Toast.LENGTH_SHORT).show()
                            } else {
                                Reminders.archive(this@MainActivity, r.id)
                                Toast.makeText(this@MainActivity, "已归档", Toast.LENGTH_SHORT).show()
                            }
                        } else if (!longFired && System.currentTimeMillis() - downAt < 400) {
                            showDetail(r)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
                }
                true
            }

            v.findViewById<Button>(R.id.btnOpen).apply {
                visibility = if (r.pkg.isNotBlank() || r.notifKey.isNotBlank()) View.VISIBLE else View.GONE
                setOnClickListener { openSource(r) }
            }
            v.findViewById<Button>(R.id.btnConfirm).apply {
                visibility = if (r.archived) View.GONE else View.VISIBLE
                setOnClickListener {
                    Reminders.confirm(this@MainActivity, r.id)
                    Toast.makeText(this@MainActivity, "已彻底关闭，这条以后不会再响", Toast.LENGTH_SHORT).show()
                }
            }
            reminderContainer.addView(v)
        }
    }

    /** 长按菜单：平时不显示修改/删除按钮。 */
    private fun showItemMenu(r: Reminder) {
        val actions = if (r.archived) arrayOf("恢复", "删除") else arrayOf("修改", "删除")
        MaterialAlertDialogBuilder(this)
            .setTitle(r.title)
            .setItems(actions) { _, which ->
                if (r.archived) {
                    if (which == 0) {
                        Reminders.restore(this, r.id)
                        Toast.makeText(this, "已恢复到待处理", Toast.LENGTH_SHORT).show()
                    } else {
                        Reminders.delete(this, r.id)
                    }
                } else {
                    if (which == 0) showEdit(r) else Reminders.delete(this, r.id)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 弹窗里正文不截断，可滚动、可复制。 */
    private fun showDetail(r: Reminder) {
        val tv = TextView(this).apply {
            text = buildString {
                append(Fmt.dateTime(r.time))
                if (r.timeAssumed) append("（原通知只写了日期，时间按默认估算）")
                append("\n来自 ").append(r.source)
                if (r.confirmed) append("\n已彻底关闭（以后不会再提醒）")
                else if (r.archived) append("\n已归档")
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
