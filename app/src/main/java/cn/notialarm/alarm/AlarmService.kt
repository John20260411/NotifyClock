package cn.notialarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import cn.notialarm.Notifier
import cn.notialarm.data.Prefs
import cn.notialarm.data.ReminderStore
import cn.notialarm.ui.AlarmActivity

/** 响铃中的闹钟：前台服务负责铃声和振动，并拉起确认弹窗。 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(AlarmScheduler.EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_STOP -> {
                Notifier.cancelCreated(this, id)
                stopAll(closeUi = true)
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, Prefs(this).snoozeOptions.first())
                AlarmScheduler.snooze(this, id, minutes)
                Notifier.cancelCreated(this, id)
                stopAll(closeUi = true)
                return START_NOT_STICKY
            }
        }

        val r = ReminderStore.get(this, id)
        // startForegroundService 之后必须尽快 startForeground，即使提醒已被删除
        val notification = Notifier.alarm(this, r)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_ALARM, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifier.ID_ALARM, notification)
        }
        if (r == null) {
            stopAll(closeUi = false)
            return START_NOT_STICKY
        }

        currentId = id
        stopRinging()
        val prefs = Prefs(this)
        if (prefs.sound) startSound(prefs)
        if (prefs.vibrate) startVibration()
        showPopup(id)

        val minutes = prefs.ringMinutes.let { if (it <= 0) Prefs.MAX_RING_MINUTES else minOf(it, Prefs.MAX_RING_MINUTES) }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            // 一直没人理：停止响铃，弹窗留着，再留一条带“延后”按钮的通知
            ReminderStore.get(this, currentId)?.let { Notifier.missed(this, it) }
            stopAll(closeUi = false)
        }, minutes * 60_000L)
        return START_NOT_STICKY
    }

    /**
     * 锁屏时全屏通知会自动拉起弹窗；亮屏时系统只显示横幅，
     * 所以这里再直接启动一次（需要“悬浮窗/后台弹出界面”权限，没有的话系统会静默拦截）。
     */
    private fun showPopup(id: Int) {
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .putExtra(AlarmScheduler.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        }
    }

    private fun startSound(prefs: Prefs) {
        val attrs = AudioAttributes.Builder()
            .setUsage(if (prefs.useMediaVolume) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM)
            .setContentType(
                if (prefs.useMediaVolume) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION
            )
            .build()
        // 自选铃声失效（文件被删、权限丢失）时退回系统闹钟铃声，再退回通知音
        val candidates = listOfNotNull(
            prefs.soundUri.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            player = runCatching { play(this, uri, attrs) }.getOrNull()
            if (player != null) return
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        v.vibrate(effect, attrs)
    }

    private fun stopRinging() {
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun stopAll(closeUi: Boolean) {
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (closeUi) onStopped?.invoke()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "cn.notialarm.STOP"
        const val ACTION_SNOOZE = "cn.notialarm.SNOOZE"
        const val EXTRA_MINUTES = "minutes"

        /** 弹窗显示时设置，用户在通知上点了“知道了/延后”后关掉弹窗。 */
        var onStopped: (() -> Unit)? = null

        /** 循环播放；设置页“试听”也用它。 */
        fun play(ctx: Context, uri: Uri, attrs: AudioAttributes): MediaPlayer = MediaPlayer().apply {
            try {
                setAudioAttributes(attrs)
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            } catch (e: Exception) {
                release()
                throw e
            }
        }
    }
}
