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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class RunService : Service() {

    companion object {
        // 渠道 ID 带版本号：渠道的「重要性」一旦在系统里建过就改不动了，
        // 想改必须换新 ID，旧的自动作废（旧渠道留在地上不影响使用）。
        const val CHANNEL = "campusrun.progress.v2"
        const val CHANNEL_OLD = "campusrun.progress"
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
        acquireWakeLock()
        // 不再 RunController.addListener(this)。
        //
        // 以前靠工作线程 push 的那一刻刷新，可批次边界那一次 push 会丢
        // （表现：批次之间每秒都正常，上传那一瞬刷不动，之后就卡在那个值上）。
        // 现在改成自己起一个 1 秒的 ticker：哪一次推丢了都不影响，下一秒捡回来。
        ui.post(ticker)
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
        ui.removeCallbacks(ticker)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // 旧的 LOW 渠道删掉。LOW 的通知会被塞进「静默通知 / 服务提醒」那个折叠区，
        // 那块区域很多 ROM 不实时重绘，进度条看着就是冻住的。
        try {
            nm.deleteNotificationChannel(CHANNEL_OLD)
        } catch (_: Throwable) {
        }

        val ch = NotificationChannel(
            CHANNEL, "打卡进度", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "校园跑打卡时的实时进度"
            setShowBadge(false)
            setSound(null, null)          // DEFAULT 级别但不响
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
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

        // 正文要短 —— 折叠状态下太长会被系统截成 "..."。
        // 「预计 18:42 完成」那种写法字数压不下来，改回「剩 07:12」。
        // 刷新靠下面 refresh() 里的 startForeground()，一秒一次。
        val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"
        val line = "%.2f/%.2f km　·　剩余 %s"
            .format(s.metersDone / 1000.0, s.metersTotal / 1000.0, eta)

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("校园跑进行中　$pct%")
            .setContentText(line)
            .setProgress(100, pct, false)
            .setOngoing(true)
            // 不要 setOnlyAlertOnce(true)：
            // 有一部分 ROM 把它理解成「这条通知的更新不必重绘」，
            // 于是后台的刷新直接被丢掉。渠道已经静音（setSound(null)），
            // 不会有声音打扰，没必要再开这个。
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 主线程 ticker。 */
    private val ui = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!RunController.running) {
                stopSelf()
                return
            }
            refresh()
            ui.postDelayed(this, 1_000L)
        }
    }

    /** 上一次发通知的内容依据。内容没变就不重发。 */
    private var lastKey: String? = null

    /**
     * 每秒刷新一次通知。
     *
     * 值从 [RunController.state] 直接取 —— 那是工作线程写的、[RunController] 保证可见的
     * 最新快照，不依赖某一次 push 有没有送到这里。
     */
    private fun refresh() {
        val s = RunController.state

        // 按**最终内容**去重：进度、里程、剩余时间、状态都一样就不重发。
        // 正文里有「剩 HH:MM」，每秒都在变，所以实际是一秒一条。
        val pct = if (s.metersTotal > 0)
            (s.metersDone / s.metersTotal * 100).toInt().coerceIn(0, 100) else 0
        val key = "$pct|${s.metersDone}|${s.etaSec}|${s.phase}"
        if (key == lastKey) return

        try {
            // 用 startForeground() 而不是 nm.notify() 来更新。
            //
            // 这条通知是**前台服务的通知**，它的"所有权"在 ActivityManager 那边，
            // 不在 NotificationManager。部分 ROM（这台就是）对 nm.notify 更新
            // 前台服务通知这条路径不做重绘 —— 调用成功、没有异常、日志也写了，
            // 但通知栏纹丝不动。再调一次 startForeground() 才是官方说法。
            startForeground(NOTI_ID, build(s))
            // 只有发成功才锁 key。以前先锁再发，一次异常就永久跳过。
            lastKey = key
        } catch (_: Throwable) {
        }
    }
}
