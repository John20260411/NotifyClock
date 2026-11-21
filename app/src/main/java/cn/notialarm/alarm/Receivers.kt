package cn.notialarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.notialarm.NotifyListener
import cn.notialarm.Reminders

/** 闹钟到点 -> 启动前台服务响铃、振动、弹窗。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = Intent(ctx, AlarmService::class.java).putExtras(intent)
        ContextCompat.startForegroundService(ctx, svc)
    }
}

/** 开机或更新 App 后，系统会清掉所有闹钟，需要重新排。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotifyListener.rebind(ctx)
        }
    }
}

/** “已创建提醒”通知上的“撤销”按钮。 */
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        if (id > 0) Reminders.delete(ctx, id)
    }
}
