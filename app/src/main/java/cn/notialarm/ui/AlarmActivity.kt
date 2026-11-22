package cn.notialarm.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import cn.notialarm.Fmt
import cn.notialarm.Notifier
import cn.notialarm.R
import cn.notialarm.SourceLauncher
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.alarm.AlarmService
import cn.notialarm.data.Prefs
import cn.notialarm.data.ReminderStore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 到点时的全屏确认页：仅关闭本次 / 彻底关闭 / 打开原通知 / 延后。 */
class AlarmActivity : AppCompatActivity() {

    private val finisher: () -> Unit = { runOnUiThread { finish() } }
    private var id = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        setFinishOnTouchOutside(false)

        findViewById<Button>(R.id.btnStop).setOnClickListener { send(AlarmService.ACTION_STOP) }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener { send(AlarmService.ACTION_CONFIRM) }
        findViewById<Button>(R.id.btnOpen).setOnClickListener { openOriginal() }

        // 返回键等同“仅关闭本次响铃”：以前会 Toast 拦住用户，横屏时又点不到按钮，等于退不出去。
        onBackPressedDispatcher.addCallback(this) { send(AlarmService.ACTION_STOP) }

        val chips = findViewById<ChipGroup>(R.id.snoozeChips)
        for (min in Prefs(this).snoozeOptions.filter { it != 60 }) {
            chips.addView(Chip(this).apply {
                text = Fmt.minutes(min)
                setOnClickListener { send(AlarmService.ACTION_SNOOZE, min) }
            })
        }
        chips.addView(Chip(this).apply {
            text = getString(R.string.snooze_custom)
            setOnClickListener { askCustomSnooze() }
        })

        layoutActions()
        AlarmService.onStopped = finisher
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutActions()
    }

    /** 横屏时三个按钮并排、矮一点；竖屏时上下排，保证都在屏幕内。 */
    private fun layoutActions() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val row = findViewById<LinearLayout>(R.id.actionRow)
        val buttons = listOf(
            findViewById<Button>(R.id.btnStop),
            findViewById<Button>(R.id.btnConfirm),
            findViewById<Button>(R.id.btnOpen),
        )
        row.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        buttons.forEachIndexed { i, b ->
            b.textSize = if (landscape) 14f else 16f
            val lp = if (landscape) {
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = if (i == 0) 0 else dp(6)
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = if (i == 0) 0 else dp(6)
                }
            }
            b.layoutParams = lp
        }
    }

    private fun bind(intent: Intent) {
        id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val r = ReminderStore.get(this, id) ?: run { finish(); return }
        // 已归档的提醒（确认不再提醒/已过期）不应该再出现响铃页。
        if (r.archived) { finish(); return }
        findViewById<TextView>(R.id.tvTitle).text = r.title
        findViewById<TextView>(R.id.tvWhen).text = buildString {
            append(Notifier.whenText(this@AlarmActivity, r))
            if (r.timeAssumed) append(getString(R.string.alarm_assumed))
        }
        findViewById<TextView>(R.id.tvSource).text = getString(R.string.alarm_source, r.source)
        findViewById<TextView>(R.id.tvBody).text = r.text.ifBlank { getString(R.string.alarm_no_body) }

        if (intent.getBooleanExtra(Notifier.EXTRA_JUMP, false)) {
            intent.removeExtra(Notifier.EXTRA_JUMP)
            openOriginal()
        }
    }

    private fun openOriginal() {
        val r = ReminderStore.get(this, id)
        if (r == null) {
            finish()
            return
        }
        if (SourceLauncher.open(this, r)) {
            // 已经跳到微信/QQ，这一次响铃就可以停了
            send(AlarmService.ACTION_STOP)
        } else {
            Toast.makeText(this, R.string.alarm_jump_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun askCustomSnooze() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hint_custom_snooze)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_custom_snooze)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val min = input.text.toString().toIntOrNull()
                if (min != null && min in 1..24 * 60) send(AlarmService.ACTION_SNOOZE, min)
                else Toast.makeText(this, R.string.invalid_snooze, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun send(action: String, minutes: Int = 0) {
        startService(
            Intent(this, AlarmService::class.java).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, id).putExtra(AlarmService.EXTRA_MINUTES, minutes)
        )
        when (action) {
            AlarmService.ACTION_STOP -> {
                val stillRinging = ReminderStore.get(this, id)
                    ?.let { AlarmScheduler.plan(this, it).isNotEmpty() } == true
                Toast.makeText(
                    this,
                    if (stillRinging) getString(R.string.alarm_closed_once) else getString(R.string.alarm_closed_once_only),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            AlarmService.ACTION_SNOOZE ->
                Toast.makeText(this, getString(R.string.alarm_snoozed, Fmt.minutes(minutes)), Toast.LENGTH_SHORT).show()
            AlarmService.ACTION_CONFIRM ->
                Toast.makeText(this, R.string.alarm_confirmed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        visible = false
        if (AlarmService.onStopped === finisher) AlarmService.onStopped = null
        super.onDestroy()
    }

    companion object {
        /** 响铃页是否真的显示在前台；没显示时不要撤掉高优先级通知。 */
        @Volatile
        var visible = false
    }
}
