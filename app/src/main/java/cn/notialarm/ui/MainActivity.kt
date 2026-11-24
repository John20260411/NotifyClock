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
    private lateinit var mainScroll: ScrollView
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
        // 0.3.3 修复排程窗口后，需要把既有提醒重新排一遍，否则旧版本保存的提醒仍然没有闹钟。
        runCatching { Reminders.rescheduleAll(this) }
        window.navigationBarColor = getColor(R.color.app_background)
        window.statusBarColor = getColor(R.color.app_background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = true
            isAppearanceLightStatusBars = true
        }

        reminderList = findViewById(R.id.reminderList)
        mainScroll = findViewById(R.id.mainScroll)
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
                    toast(getString(R.string.toast_restored))
                } else {
                    showEdit(it)
                }
            },
            onOpen = { openSource(it) },
            onConfirm = {
                if (it.archived) {
                    Reminders.delete(this, it.id)
                    toast(getString(R.string.toast_deleted))
                } else {
                    Reminders.confirm(this, it.id)
                    toast(getString(R.string.toast_closed_forever))
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
            Processor.submit(applicationContext, getString(R.string.manual_source), text) { r, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    renderLog()
                    if (r != null) {
                        focusReminder(r)
                        input.text.clear()
                    } else {
                        renderReminders()
                    }
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
            toast(getString(R.string.toast_cleared_dropped))
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
        if (handleExternalText(intent)) return
        val id = intent?.getIntExtra(Notifier.EXTRA_EDIT_ID, -1) ?: -1
        if (id > 0) ReminderStore.get(this, id)?.let { showEdit(it) }
    }

    /**
     * 在微信/QQ 聊天界面里没有系统通知时，可以从文字选择菜单“处理文本”或系统分享发送到本应用。
     * 这是零额外权限的兜底；自动读取前台聊天内容需要用户另行开启无障碍服务。
     */
    private fun handleExternalText(intent: Intent?): Boolean {
        if (intent == null) return false
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()
        if (text.isNullOrEmpty()) return false
        intent.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent.removeExtra(Intent.EXTRA_TEXT)
        window.decorView.post {
            val input = findViewById<EditText>(R.id.inputText)
            input.setText(text)
            input.setSelection(text.length)
            mainScroll.smoothScrollTo(0, 0)
            findViewById<Button>(R.id.btnParse).performClick()
        }
        return true
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
            getString(R.string.perm_warning_access)
        } else {
            getString(R.string.perm_warning_disconnected)
        }
    }

    private fun renderReminders() {
        val active = ReminderStore.active(this)
        val archived = ReminderStore.archived(this)
        btnActive.text = getString(R.string.tab_active) + " " + active.size
        btnArchived.text = getString(R.string.tab_archived) + " " + archived.size

        val list = if (showArchived) archived else active
        tvListHint.text = if (showArchived) {
            getString(R.string.list_hint_archived)
        } else {
            getString(R.string.list_hint_active)
        }
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (showArchived) {
            getString(R.string.empty_archived)
        } else {
            getString(R.string.empty_active)
        }
        adapter.submit(list)
        resizeReminderList()
    }

    /**
     * RecyclerView 在 ScrollView 里使用 wrap_content 时只会量到首屏几条，
     * 后面的提醒虽然存在于 adapter 里却不会显示。按卡片真实高度撑开列表。
     */
    private fun resizeReminderList() {
        if (isFinishing || isDestroyed) return
        val width = reminderList.width - reminderList.paddingLeft - reminderList.paddingRight
        if (width <= 0) {
            reminderList.post { resizeReminderList() }
            return
        }
        val height = if (adapter.itemCount == 0) 0 else adapter.contentHeight(reminderList, width)
        if (reminderList.layoutParams.height != height) {
            reminderList.layoutParams = reminderList.layoutParams.apply { this.height = height }
        }
    }

    /**
     * 创建成功后把用户带到这条提醒：切回“待处理”，滚动到卡片位置。
     * 列表按时间升序，新任务可能在屏幕外；不滚动就会让人以为“没有创建”。
     */
    private fun focusReminder(r: Reminder) {
        if (showArchived) {
            showArchived = false
            toggleGroup.check(R.id.btnActive)
        }
        renderReminders()
        reminderList.post outer@{
            resizeReminderList()
            reminderList.post inner@{
                val index = adapter.indexOf(r.id)
                if (index < 0) return@inner
                reminderList.scrollToPosition(index)
                val vh = reminderList.findViewHolderForAdapterPosition(index)
                val target = if (vh != null) {
                    reminderList.top + vh.itemView.top - dp(16)
                } else {
                    reminderList.top
                }
                mainScroll.smoothScrollTo(0, target.coerceAtLeast(0))
            }
        }
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
                    toast(getString(R.string.toast_restored))
                } else {
                    Reminders.archive(this@MainActivity, r.id)
                    toast(getString(R.string.toast_archived))
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
                    val label = getString(if (archived) R.string.action_restore else R.string.tab_archived)
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
            .setTitle(R.string.dialog_delete_title)
            .setMessage(r.title)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Reminders.delete(this, r.id)
                toast(getString(R.string.toast_deleted))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 详情弹窗：正文可滚动可复制，底部固定三个动作。 */
    private fun showDetail(r: Reminder) {
        val v = layoutInflater.inflate(R.layout.dialog_detail, null)
        val dm = resources.displayMetrics
        val body = r.text.ifBlank { getString(R.string.detail_no_body) }
        val lines = body.length / 18 + 1
        val bodyHeight = min(dp(320), max(dp(64), dp(24 * min(lines, 12) + 16)))
        v.findViewById<ScrollView>(R.id.dlgBodyScroll).layoutParams =
            v.findViewById<ScrollView>(R.id.dlgBodyScroll).layoutParams.apply {
                height = min(bodyHeight, (dm.heightPixels * 0.42f).toInt())
            }
        v.findViewById<TextView>(R.id.dlgTitle).text = r.title
        v.findViewById<TextView>(R.id.dlgWhen).text = buildString {
            append(Fmt.dateTime(r.time))
            if (r.timeAssumed) append(getString(R.string.detail_assumed))
        }
        v.findViewById<TextView>(R.id.dlgSource).text = buildString {
            append(getString(R.string.source_fmt, r.source))
            if (r.confirmed) append(getString(R.string.detail_closed_suffix))
            else if (r.archived) append(getString(R.string.detail_archived_suffix))
        }
        v.findViewById<TextView>(R.id.dlgBody).text = r.text.ifBlank { getString(R.string.detail_no_body) }

        val b1 = v.findViewById<MaterialButton>(R.id.dlgBtn1)
        val b2 = v.findViewById<MaterialButton>(R.id.dlgBtn2)
        val b3 = v.findViewById<MaterialButton>(R.id.dlgBtn3)
        val dialog = MaterialAlertDialogBuilder(this).setView(v).create()

        if (r.archived) {
            b1.text = getString(R.string.action_restore)
            b3.text = getString(R.string.action_delete)
            b1.setOnClickListener {
                Reminders.restore(this, r.id)
                dialog.dismiss()
                toast(getString(R.string.toast_restored))
            }
            b3.setOnClickListener {
                Reminders.delete(this, r.id)
                dialog.dismiss()
                toast(getString(R.string.toast_deleted))
            }
        } else {
            b1.text = getString(R.string.action_edit)
            b3.text = getString(R.string.action_confirm_off)
            b1.setOnClickListener {
                dialog.dismiss()
                showEdit(r)
            }
            b3.setOnClickListener {
                Reminders.confirm(this, r.id)
                dialog.dismiss()
                toast(getString(R.string.toast_closed_forever))
            }
        }
        b2.text = getString(R.string.action_open_source)
        b2.setOnClickListener { openSource(r) }

        dialog.show()
        dialog.window?.setLayout(dm.widthPixels - dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun openSource(r: Reminder) {
        if (!SourceLauncher.open(this, r)) {
            Toast.makeText(this, R.string.toast_jump_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderDropped() {
        droppedContainer.removeAllViews()
        // 只保留用户明确勾选的 App；未勾选 App 的历史记录一并清掉。
        DroppedStore.retainPackages(this, Prefs(this).monitoredPackages)
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
            v.findViewById<TextView>(R.id.dropReason).text = getString(R.string.dropped_count, localizedReason(d.reason), d.count)
            v.findViewById<TextView>(R.id.dropText).text = d.text.ifBlank { getString(R.string.detail_no_body) }

            v.findViewById<Button>(R.id.btnBlock).apply {
                text = getString(if (d.conversation.isNotBlank()) R.string.block_conversation else R.string.block_app)
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
                    toast(getString(R.string.toast_never_record))
                    renderDropped()
                }
            }
            v.findViewById<Button>(R.id.btnRetry).setOnClickListener {
                val incoming = Incoming(
                    d.pkg, d.app, d.conversation, d.title, d.text, "", null,
                    System.currentTimeMillis(),
                    force = true,
                )
                Processor.submit(applicationContext, incoming) { r, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (r != null) {
                            DroppedStore.remove(this, d)
                            focusReminder(r)
                        }
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
        logText.text = if (lines.isEmpty()) getString(R.string.recent_empty) else lines.joinToString("\n")
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
            tvSource.text = getString(R.string.old_text_label, r.source, r.text)
            tvSource.visibility = View.VISIBLE
            tvSource.setTypeface(null, Typeface.ITALIC)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (r == null) R.string.dialog_new_title else R.string.dialog_edit_title)
            .setView(v)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim().ifBlank { getString(R.string.default_reminder_title) }
                val time = runCatching {
                    LocalDateTime.parse(etTime.text.toString().trim(), Fmt.INPUT)
                }.getOrNull()
                val offsets = Prefs.parseOffsets(etOffsets.text.toString())
                when {
                    time == null -> etTime.error = getString(R.string.error_time)
                    offsets == null -> etOffsets.error = getString(R.string.error_offsets_short)
                    r == null -> {
                        val created = Reminders.create(this, title, time, getString(R.string.manual_source), "", offsets)
                        dialog.dismiss()
                        if (created != null) {
                            toast(getString(R.string.toast_created))
                            focusReminder(created)
                        } else {
                            toast(getString(R.string.toast_duplicate))
                        }
                    }
                    else -> {
                        Reminders.update(this, r.copy(title = title, time = time, offsets = offsets))
                        dialog.dismiss()
                        toast(getString(R.string.toast_edit_saved))
                    }
                }
            }
        }
        dialog.show()
    }

    /** 把历史 Dropped 记录里的中文原因映射为当前界面语言。 */
    private fun localizedReason(reason: String): String = when {
        reason.startsWith("通知没有正文") -> getString(R.string.dropped_reason_no_body)
        reason.startsWith("群聊汇总") -> getString(R.string.dropped_reason_summary)
        reason.startsWith("常驻通知") -> getString(R.string.dropped_reason_ongoing)
        reason.startsWith("这个 App 不在监听列表") -> getString(R.string.dropped_reason_not_selected)
        else -> getString(R.string.dropped_reason_no_time)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
