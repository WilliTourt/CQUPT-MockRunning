/*
 * 前台服务：只为"保命"。
 *
 * 手机揣兜里跑 11 分钟，App 在后台很容易被系统回收。挂上前台服务 + 常驻通知，
 * 进程就不会被随手杀掉；通知栏里还能直接看到进度。
 *
 * 不申请任何定位权限。
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class RunService : Service(), RunListener {

    companion object {
        const val CHANNEL = "campusrun.progress"
        const val NOTI_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, RunService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (_: Throwable) {
                // 拿不到前台服务也不致命，只是退化成普通后台线程
            }
        }
    }

    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        startForeground(NOTI_ID, build(RunController.state))
        RunController.addListener(this)
        acquireWakeLock()
    }

    /**
     * 拿一把 CPU 锁。
     *
     * 前台服务能保证进程不被回收，但**挡不住 CPU 被挂起** ——
     * 手机揣兜里息屏之后，系统会把 CPU 停掉，Thread.sleep 就会睡过头，
     * 整轮打卡被拖成一倍长。这把锁让 CPU 一直醒着。
     *
     * 注意：深度 Doze 下系统会连 wake lock 一起忽略，
     * 所以还需要在界面里引导用户把本 App 加进电池优化白名单。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "campusrun:run").apply {
                setReferenceCounted(false)
                acquire(70 * 60 * 1000L)      // 兜底 70 分钟，够跑好几轮
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RunController.running) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RunController.removeListener(this)
        releaseWakeLock()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 也不要停
        if (!RunController.running) stopSelf()
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "打卡进度", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "校园跑打卡时的实时进度"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun build(s: RunState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pct = if (s.metersTotal > 0)
            (s.metersDone / s.metersTotal * 100).toInt().coerceIn(0, 100) else 0

        val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"
        val line = "%.2f/%.2f km　·　剩 %s　·　%s"
            .format(s.metersDone / 1000.0, s.metersTotal / 1000.0, eta, s.phase)

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("校园跑进行中　$pct%")
            .setContentText(line)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun log(line: String) {
        // 通知里不显示日志，忽略
    }

    override fun onState(s: RunState) {
        if (!RunController.running) {
            stopSelf()
            return
        }
        try {
            nm.notify(NOTI_ID, build(s))
        } catch (_: Throwable) {
        }
    }
}
