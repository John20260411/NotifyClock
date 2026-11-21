package cn.notialarm.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import cn.notialarm.Fmt
import cn.notialarm.Notifier
import cn.notialarm.R
import cn.notialarm.alarm.AlarmScheduler
import cn.notialarm.alarm.AlarmService
import cn.notialarm.data.Prefs
import cn.notialarm.data.ReminderStore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 到点时的确认弹窗：知道了 / 延后一段时间。锁屏和亮屏都会弹出。 */
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

        // 必须明确选择，不能按返回键糊弄过去
        onBackPressedDispatcher.addCallback(this) {
            Toast.makeText(this@AlarmActivity, "请选择“知道了”或延后时间", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener { send(AlarmService.ACTION_STOP) }

        val chips = findViewById<ChipGroup>(R.id.snoozeChips)
        for (min in Prefs(this).snoozeOptions) {
            chips.addView(Chip(this).apply {
                text = Fmt.minutes(min)
                setOnClickListener { send(AlarmService.ACTION_SNOOZE, min) }
            })
        }
        chips.addView(Chip(this).apply {
            text = "自定义…"
            setOnClickListener { askCustomSnooze() }
        })

        AlarmService.onStopped = finisher
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val r = ReminderStore.get(this, id) ?: run { finish(); return }
        findViewById<TextView>(R.id.tvTitle).text = r.title
        findViewById<TextView>(R.id.tvWhen).text = Notifier.whenText(r)
        findViewById<TextView>(R.id.tvSource).apply {
            text = if (r.text.isBlank()) "" else "${r.source}：${r.text.take(200)}"
            visibility = if (r.text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun askCustomSnooze() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "分钟数，如 20"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("延后多少分钟？")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val min = input.text.toString().toIntOrNull()
                if (min != null && min in 1..24 * 60) send(AlarmService.ACTION_SNOOZE, min)
                else Toast.makeText(this, "请输入 1–1440 之间的分钟数", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun send(action: String, minutes: Int = 0) {
        startService(
            Intent(this, AlarmService::class.java).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, id).putExtra(AlarmService.EXTRA_MINUTES, minutes)
        )
        if (action == AlarmService.ACTION_SNOOZE) {
            Toast.makeText(this, "将在${Fmt.minutes(minutes)}后再次提醒", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onDestroy() {
        if (AlarmService.onStopped === finisher) AlarmService.onStopped = null
        super.onDestroy()
    }
}
