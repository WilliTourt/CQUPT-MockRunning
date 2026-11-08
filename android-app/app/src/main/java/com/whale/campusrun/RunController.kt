/*
 * 跑步状态中枢：
 *   - 线程由单例持有，不挂在 Activity 上 —— Activity 被系统重建也不会打断跑步
 *   - 日志和 RunState 缓存在这里，Activity 重新附着时能完整恢复
 *   - 前台服务只需读这里就能刷新通知栏
 * 作者：蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object RunController {

    private const val MAX_LOG = 400

    /**
     * 跑完（或失败）之后，仪表盘停留这么久让人看清最终成绩，然后自己回到空闲态。
     *
     * 不留的话就会停在最后那一帧上 —— 「已完成 99%」挂在主页不走，
     * 得等系统把进程杀掉再进来才干净。那是碰运气，不是收尾。
     */
    private const val IDLE_RESET_DELAY_MS = 6000L

    /** 由 MainActivity 在 onCreate 时塞进来，用于拉起前台服务。 */
    @Volatile
    private var appCtx: Context? = null

    fun attachApp(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 日志缓冲。Activity 重建后靠它恢复。 */
    private val logBuffer = ArrayList<String>()

    @Volatile
    var state: RunState = RunState()
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var cancelling: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<RunListener>()
    private var thread: Thread? = null
    private var engine: RunEngine? = null

    /** 主线程 Handler，只用来做「延迟归零」。 */
    private val ui = Handler(Looper.getMainLooper())

    /** 延迟归零的任务。`start()` 时要撤掉，免得新一轮刚起来就被它清掉。 */
    private val idleReset = Runnable { resetDashboard() }

    /**
     * 把仪表盘清回空闲态。
     *
     * **不动日志缓冲** —— 那是这一轮跑步的记录，跑完正是要翻它的时候。
     * 日志只在新一轮 `start()` 时清。
     */
    fun resetDashboard() {
        if (running) return
        state = RunState()
        emitState()
    }

    private fun scheduleIdleReset() {
        ui.removeCallbacks(idleReset)
        ui.postDelayed(idleReset, IDLE_RESET_DELAY_MS)
    }

    // ---------------------------------------------------------------- 观察者

    fun addListener(l: RunListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: RunListener) {
        listeners.remove(l)
    }

    /** 给刚附着的 Activity 用的快照。 */
    fun logSnapshot(): List<String> = synchronized(logBuffer) { ArrayList(logBuffer) }

    // ---------------------------------------------------------------- 生命周期

    fun start(
        openid: String,
        area: Area,
        paceSecKm: Double,
        targetM: Double,
        noise: Double,
        seed: Int,
        paceToleranceSec: Double = TrackGen.DEFAULT_PACE_TOLERANCE_SEC,
        burrs: Boolean = false
    ) {
        if (running) return
        running = true
        cancelling = false
        // 上一轮可能还排着一个延迟归零，先撤掉，不然新一轮刚起来就被它清空
        ui.removeCallbacks(idleReset)
        logBuffer.clear()
        state = RunState(phase = "准备中", paceSecKm = paceSecKm, metersTotal = targetM)
        emitState()

        val engine = RunEngine(Api(), callback)
        this.engine = engine

        // 先挂前台服务保命，再开工 —— 手机揣兜里 11 分钟，进程不能被杀
        appCtx?.let { RunService.start(it) }

        thread = Thread({
            try {
                engine.runAll(
                    openid = openid,
                    area = area,
                    paceSecKm = paceSecKm,
                    targetM = targetM,
                    realtime = true,          // 服务端按墙钟算配速，必须超实时
                    noise = noise,
                    seed = seed,
                    paceToleranceSec = paceToleranceSec,
                    burrs = burrs
                )
            } catch (t: Throwable) {
                log("✗ 运行异常：${t.javaClass.name}: ${t.message}")
                t.stackTrace.take(8).forEach { log("      at $it") }
                state = state.copy(phase = "崩溃：${t.javaClass.simpleName}", failed = true)
                emitState()
            } finally {
                running = false
                cancelling = false
                thread = null
                // 关键：停下来的状态要再广播一次，
                // 否则界面最后一次收到的是 running 还是 true 的那一帧，
                // 按钮就永远停在"结束打卡"上变不回去了。
                emitState()
                // 让「已完成 / 失败」的成绩在屏幕上留一会儿，然后自己回空闲态
                scheduleIdleReset()
                appCtx?.let { c ->
                    try {
                        c.stopService(android.content.Intent(c, RunService::class.java))
                    } catch (_: Throwable) {
                    }
                }
            }
        }, "campusrun-worker")
        thread?.start()
    }

    fun cancel() {
        if (!running) return
        cancelling = true
        engine?.cancel()
        log("正在停止…（当前批次传完就停）")
    }

    // ---------------------------------------------------------------- 内部回调

    private val callback = object : RunListener {
        override fun log(line: String) {
            appendLog(line)
        }

        override fun onState(s: RunState) {
            state = s
            emitState()
        }
    }

    private fun appendLog(line: String) {
        val stamped = "[${JsClock.hms()}] $line"
        synchronized(logBuffer) {
            logBuffer.add(stamped)
            while (logBuffer.size > MAX_LOG) logBuffer.removeAt(0)
        }
        for (l in listeners) {
            try {
                l.log(stamped)
            } catch (_: Throwable) {
            }
        }
    }

    private fun log(line: String) = appendLog(line)

    private fun emitState() {
        for (l in listeners) {
            try {
                l.onState(state)
            } catch (_: Throwable) {
            }
        }
    }

    /** 供外部（Activity）写日志，不落缓冲。 */
    fun logFromUi(line: String) = appendLog(line)
}

internal object JsClock {
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
    fun hms(): String = fmt.format(java.util.Date())
}
