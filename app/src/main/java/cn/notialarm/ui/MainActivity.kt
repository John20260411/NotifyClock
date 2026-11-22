package cn.notialarm.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.notialarm.Fmt
import cn.notialarm.Notifier
import cn.notialarm.NotifyListener
import cn.notialarm.Processor
import cn.notialarm.R
import cn.notialarm.Reminders
import cn.notialarm.SourceLauncher
import cn.notialarm.data.ActivityLog
import cn.notialarm.data.DroppedStore
import cn.notialarm.data.Incoming
import cn.notialarm.data.Prefs
import cn.notialarm.data.Reminder
import cn.notialarm.data.ReminderStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var reminderList: RecyclerView
    private lateinit var adapter: ReminderAdapter
    private lateinit var droppedContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyDropped: TextView
    private lateinit var logText: TextView
    private lateinit var tvPermWarning: TextView
    private lateinit var tvListHint: TextView
    private lateinit var btnActive: MaterialButton
    private lateinit var btnArchived: MaterialButton
    private lateinit var toggleGroup: MaterialButtonToggleGroup

    /** false = 看待处理；true = 看归档。 */
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannels(this)
        window.navigationBarColor = getColor(R.color.app_background)
        window.statusBarColor = getColor(R.color.app_background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = true
            isAppearanceLightStatusBars = true
        }

        reminderList = findViewById(R.id.reminderList)
        droppedContainer = findViewById(R.id.droppedContainer)
        emptyText = findViewById(R.id.emptyText)
        emptyDropped = findViewById(R.id.emptyDropped)
        logText = findViewById(R.id.logText)
        tvPermWarning = findViewById(R.id.tvPermWarning)
        tvListHint = findViewById(R.id.tvListHint)
        btnActive = findViewById(R.id.btnActive)
        btnArchived = findViewById(R.id.btnArchived)
        toggleGroup = findViewById(R.id.toggleGroup)

        adapter = ReminderAdapter(
            onOpenDetail = { showDetail(it) },
            onLongPress = { confirmDelete(it) },
            onEdit = {
                if (it.archived) {
                    Reminders.restore(this, it.id)
                    toast("已恢复到待处理")
                } else {
                    showEdit(it)
                }
            },
            onOpen = { openSource(it) },
            onConfirm = {
                if (it.archived) {
                    Reminders.delete(this, it.id)
                    toast("已删除")
                } else {
                    Reminders.confirm(this, it.id)
                    toast("已彻底关闭，这条以后不会再响")
                }
            }
        )
        reminderList.layoutManager = LinearLayoutManager(this)
        reminderList.adapter = adapter
        // 外层是 ScrollView：关掉 RecyclerView 自己的增删动画，避免 wrap_content 高度残留空白
        reminderList.itemAnimator = null
        attachSwipeToArchive()

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

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
            toast("已清空未记录列表")
        }
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            showArchived = checkedId == R.id.btnArchived
            renderReminders()
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
        Reminders.onChange = {
            renderReminders()
            renderLog()
            renderDropped()
            renderPermWarning()
        }
        NotifyListener.onStateChanged = { renderPermWarning() }
        if (Permissions.listenerGranted(this) && !NotifyListener.connected) NotifyListener.rebind(this)
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
        val active = ReminderStore.active(this)
        val archived = ReminderStore.archived(this)
        btnActive.text = "待处理 ${active.size}"
        btnArchived.text = "归档 ${archived.size}"

        val list = if (showArchived) archived else active
        tvListHint.text = if (showArchived) {
            "点按查看全文 · 左滑恢复 · 长按删除"
        } else {
            "点按查看全文 · 左滑归档 · 长按删除"
        }
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (showArchived) {
            "归档是空的。已过期或你点过“彻底关闭”的提醒会放在这里。"
        } else {
            "还没有待处理提醒。收到含时间的通知后会自动出现在这里。"
        }
        adapter.submit(list)
    }

    /** 左滑归档/恢复：阈值 26%，快速轻扫也能触发；滑动时露出圆角色块和文字。 */
    private fun attachSwipeToArchive() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.26f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 0.35f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val r = adapter.itemAt(viewHolder.bindingAdapterPosition) ?: return
                if (r.archived) {
                    Reminders.restore(this@MainActivity, r.id)
                    toast("已恢复到待处理")
                } else {
                    Reminders.archive(this@MainActivity, r.id)
                    toast("已归档，可在“归档”里恢复")
                }
                renderReminders()
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                if (dX < 0 && actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val r = adapter.itemAt(viewHolder.bindingAdapterPosition)
                    val archived = r?.archived == true
                    val bg = getColor(if (archived) R.color.swipe_restore else R.color.swipe_archive)
                    val rect = RectF(
                        item.left.toFloat(),
                        item.top.toFloat(),
                        item.right.toFloat(),
                        item.bottom.toFloat()
                    )
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
                    val radius = dp(20).toFloat()
                    c.save()
                    c.clipRect(rect)
                    c.drawRoundRect(rect, radius, radius, paint)
                    val label = if (archived) "恢复" else "归档"
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = dp(14).toFloat()
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = Paint.Align.RIGHT
                    }
                    val tx = item.right - dp(22).toFloat()
                    val ty = item.top + item.height / 2f -
                        (textPaint.descent() + textPaint.ascent()) / 2f
                    c.drawText(label, tx, ty, textPaint)
                    // 轻微缩放，滑动更有“跟手”的重量感
                    val scale = 1f - min(abs(dX) / item.width * 0.03f, 0.03f)
                    item.scaleY = scale
                    c.restore()
                } else {
                    item.scaleY = 1f
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(reminderList)
    }

    private fun confirmDelete(r: Reminder) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这条提醒？")
            .setMessage(r.title)
            .setPositiveButton("删除") { _, _ ->
                Reminders.delete(this, r.id)
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 详情弹窗：正文可滚动可复制，底部固定三个动作。 */
    private fun showDetail(r: Reminder) {
        val v = layoutInflater.inflate(R.layout.dialog_detail, null)
        val dm = resources.displayMetrics
        val body = r.text.ifBlank { "（没有正文）" }
        val lines = body.length / 18 + 1
        val bodyHeight = min(dp(320), max(dp(64), dp(24 * min(lines, 12) + 16)))
        v.findViewById<ScrollView>(R.id.dlgBodyScroll).layoutParams =
            v.findViewById<ScrollView>(R.id.dlgBodyScroll).layoutParams.apply {
                height = min(bodyHeight, (dm.heightPixels * 0.42f).toInt())
            }
        v.findViewById<TextView>(R.id.dlgTitle).text = r.title
        v.findViewById<TextView>(R.id.dlgWhen).text = buildString {
            append(Fmt.dateTime(r.time))
            if (r.timeAssumed) append("（原通知只写了日期，时间按默认估算）")
        }
        v.findViewById<TextView>(R.id.dlgSource).text = buildString {
            append("来自 ").append(r.source)
            if (r.confirmed) append(" · 已彻底关闭")
            else if (r.archived) append(" · 已归档")
        }
        v.findViewById<TextView>(R.id.dlgBody).text = r.text.ifBlank { "（没有正文）" }

        val b1 = v.findViewById<MaterialButton>(R.id.dlgBtn1)
        val b2 = v.findViewById<MaterialButton>(R.id.dlgBtn2)
        val b3 = v.findViewById<MaterialButton>(R.id.dlgBtn3)
        val dialog = MaterialAlertDialogBuilder(this).setView(v).create()

        if (r.archived) {
            b1.text = "恢复"
            b3.text = "删除"
            b1.setOnClickListener {
                Reminders.restore(this, r.id)
                dialog.dismiss()
                toast("已恢复到待处理")
            }
            b3.setOnClickListener {
                Reminders.delete(this, r.id)
                dialog.dismiss()
                toast("已删除")
            }
        } else {
            b1.text = "修改"
            b3.text = "彻底关闭"
            b1.setOnClickListener {
                dialog.dismiss()
                showEdit(r)
            }
            b3.setOnClickListener {
                Reminders.confirm(this, r.id)
                dialog.dismiss()
                toast("已彻底关闭，这条以后不会再响")
            }
        }
        b2.text = "打开原通知"
        b2.setOnClickListener { openSource(r) }

        dialog.show()
        dialog.window?.setLayout(dm.widthPixels - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    toast("已加入监听：${d.app}")
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
                    toast("以后不再记录")
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
                val time = runCatching {
                    LocalDateTime.parse(etTime.text.toString().trim(), Fmt.INPUT)
                }.getOrNull()
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
