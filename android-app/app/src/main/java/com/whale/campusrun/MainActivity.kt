/*
 * 鲸鲸约跑 · 主页
 *
 * 主页**只放打卡相关设置**：场地、配速、距离、容差、开始按钮、仪表盘。
 * openid 输入在【设置】页，运行日志在【日志】页 —— 右上角两个小图标分别进入。
 *
 * 跑步线程归 RunController（单例）所有，本 Activity 只是观察者。
 * 所以退出 App、锁屏、被系统重建 —— 进度都能完整恢复。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RunListener {

    private lateinit var etPace: EditText
    private lateinit var etDist: EditText
    private lateinit var etTol: EditText
    private lateinit var spArea: Spinner
    private lateinit var btnRun: Button
    private lateinit var tvPaceWindow: TextView

    private lateinit var bar: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPaceInfo: TextView
    private lateinit var tvBatches: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvLastLog: TextView
    private lateinit var rootScroll: ScrollView

    private val api = Api()
    private lateinit var engine: RunEngine
    private val ui = Handler(Looper.getMainLooper())

    /** 当前绑进下拉框的那份场地，下标要和 adapter 一一对应，不能直接用 AreaStore 的快照。 */
    private var areas: List<Area> = emptyList()

    /** [areas] 对应 AreaStore 的哪个 revision，用来判断要不要重绑。 */
    private var boundRevision = -1L

    /** 串行化「连接 + 拉场地」，免得自动补拉和点【开始打卡】同时换 token。 */
    private val fetchLock = Any()

    /** 自动补拉是否在进行中，防止每次 onResume 都起一条线程。 */
    @Volatile
    private var autoFetching = false

    private var busy = false

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题已在 App.onCreate 里设好了（比这里早，所以不会出现"先白后黑"的闪一下）。
        // 这里不要再调 setDefaultNightMode —— 那会在 super.onCreate 之后才改，
        // 触发一次 Activity 重建，用户就看到白闪。
        setContentView(R.layout.activity_main)

        RunController.attachApp(this)
        engine = RunEngine(api, this)

        rootScroll = findViewById(R.id.scroll)
        etPace = findViewById(R.id.etPace)
        etDist = findViewById(R.id.etDist)
        etTol = findViewById(R.id.etTol)
        spArea = findViewById(R.id.spArea)
        btnRun = findViewById(R.id.btnRun)
        tvPaceWindow = findViewById(R.id.tvPaceWindow)

        bar = findViewById(R.id.bar)
        tvPercent = findViewById(R.id.tvPercent)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvPaceInfo = findViewById(R.id.tvPaceInfo)
        tvBatches = findViewById(R.id.tvBatches)
        tvServer = findViewById(R.id.tvServer)
        tvLastLog = findViewById(R.id.tvLastLog)

        etPace.setText(prefs.getString("pace", "5:45"))
        etDist.setText(prefs.getString("dist", "2.0"))
        etTol.setText(prefs.getString("paceTol", "20"))

        findViewById<ImageButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnRun.setOnClickListener { if (RunController.running) doStop() else doRun() }

        // 配速 / 容差改动时刷新"允许窗口"提示
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updatePaceWindow()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        etPace.addTextChangedListener(watcher)
        etTol.addTextChangedListener(watcher)

        updateBatteryHint()
        updatePaceWindow()
        askNotificationPermission()
        // 先给下拉框一个占位项（onResume 里的 syncAreas 会把它换成真场地）。
        // 不给的话首次启动时 Spinner 没有 adapter，看起来像个坏掉的空框。
        bindAreas(emptyList(), "")
        restoreOrInit()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onStart() {
        super.onStart()
        RunController.addListener(this)
        onState(RunController.state)
        refreshRunButton()
        val buf = RunController.logSnapshot()
        tvLastLog.text = buf.lastOrNull() ?: getString(R.string.log_hint_short)
        if (buf.isEmpty()) welcome()
    }

    override fun onStop() {
        RunController.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryHint()
        updatePaceWindow()
        // openid 是在设置页填的，填完回来必须立刻反映到场地下拉框上，
        // 而不是等用户点了一次【开始打卡】才刷新。见 syncAreas()。
        syncAreas()
    }

    private fun restoreOrInit() {
        if (RunController.running) {
            RunController.logFromUi("（已重新附着到正在进行的打卡）")
        }
    }

    // ---------------------------------------------------------------- 场地同步

    /**
     * 把 AreaStore 里的场地同步到主页下拉框，必要时补拉一次。
     *
     * 这是「填完 openid 主页不刷新」的正解：设置页那边一填 openid 就写进 prefs、
     * 拉到场地就发布到 AreaStore，主页 onResume 一比对 revision 就重绑下拉框，
     * 不需要重打网络，也不需要用户先去点一次【开始打卡】。
     *
     * 三种情况：
     *   ① 换过 openid → 旧场地作废，重新拉（换账号不能还显示别人的场地）
     *   ② 已有当前 openid 的结果 → 直接绑定
     *   ③ 还没拉过、也不在失败冷却期、openid 也没被判定无效 → 后台补拉
     */
    private fun syncAreas() {
        val oid = openid()

        // ① 设置页换了 openid：上一份场地属于别人，必须先丢
        if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
            AreaStore.invalidate()
            boundRevision = -1L
        }

        // ② 有现成的就直接绑，不再动网络
        if (AreaStore.hasResultFor(oid)) {
            bindIfChanged()
            return
        }

        // 之前绑过但现在没数据了（比如换了 openid），先把下拉框清空，别留旧场地
        if (areas.isNotEmpty()) bindList(emptyList())

        // ③ 没 openid 就没什么可拉的
        if (oid.isEmpty()) return

        // openid 已确认无效 / 刚失败过 / 正在忙：都不再自动补拉
        if (AreaStore.isBadOpenid(oid)) return
        if (AreaStore.inCooldown(oid)) return
        if (autoFetching || busy || RunController.running) return

        autoFetching = true
        Thread {
            try {
                fetchAreasFor(oid, silent = true)
            } catch (t: Throwable) {
                logStack("自动拉取场地", t)
            } finally {
                autoFetching = false
            }
        }.start()
    }

    /** revision 变了才重绑。 */
    private fun bindIfChanged() {
        if (boundRevision == AreaStore.revision) return
        bindList(AreaStore.snapshot())
    }

    /** 无条件把这份场地铺进下拉框，并记住它对应哪个 revision。 */
    private fun bindList(list: List<Area>) {
        // 先记下当前选中项，再换 areas —— 换完就读不到旧的了
        val keep = currentSelection()
        areas = list
        bindAreas(list, keep)
        boundRevision = AreaStore.revision
    }

    /** 界面上当前选中的场地编号；没绑过场地时退回 prefs 里上次打卡用的那个。 */
    private fun currentSelection(): String =
        areas.getOrNull(spArea.selectedItemPosition)?.placeCode
            ?: prefs.getString("placeCode", "").orEmpty()

    // ---------------------------------------------------------------- 配速窗口

    private fun updatePaceWindow() {
        val pace = RunEngine.parsePace(etPace.text.toString())
        val tol = etTol.text.toString().trim().toDoubleOrNull()
        if (pace == null || tol == null || pace < 60 || tol < 0) {
            tvPaceWindow.text = getString(R.string.pace_window_bad)
            return
        }
        val w = TrackGen.paceWindow(pace, tol)
        tvPaceWindow.text = getString(
            R.string.pace_window,
            RunEngine.fmtPace(w.start), RunEngine.fmtPace(w.endInclusive)
        )
    }

    // ---------------------------------------------------------------- 打卡

    private fun welcome() {
        log("鲸鲸约跑 v${BuildConfig.VERSION_NAME} · 由 WilliTourt的蓝色大肥鱼 🐟 编写")
        log("")
        log("本程序不申请定位权限，也不会打开 GPS。")
        log("① openid 在右上角【设置】里填，连一次场地；")
        log("② 回到本页设好配速 → 点【开始打卡】。")
        log("开始之后就可以退出了：锁屏、切走、揣兜里都行。")
        log("这段时间你就去操场上挨个摄像头刷脸。")
    }

    private fun openid(): String = prefs.getString("openid", "").orEmpty().trim()

    /**
     * 连接并拉取场地。必须在后台线程调用；界面更新内部自己 post 到主线程。
     *
     * 结果会发布到 [AreaStore]，这样主页和设置页共用同一份场地，谁拉都一样。
     *
     * @param silent 自动补拉时为 true —— 只更新列表和日志，失败不弹对话框。
     *   用户没主动点任何按钮，突然弹一个框太打扰；真的开始打卡前还会再拦一次。
     * @return 是否拿到了可用场地
     */
    private fun fetchAreasFor(oid: String, silent: Boolean): Boolean {
        // 同一时刻只允许一条「连接 + 拉场地」在跑。本 Activity 只有这一份 engine，
        // 自动补拉和点【开始打卡】都会走这里；串行化能避免两条线程交替换 token。
        synchronized(fetchLock) {
            // 排队等锁的这段时间里，别人（比如自动补拉）可能已经拉好了 —— 那就别重复打网络。
            if (AreaStore.hasAreasFor(oid)) {
                ui.post { bindIfChanged() }
                return true
            }
            if (!engine.connect(oid)) {
                // 换 token 失败分两种，处理方式完全不同：网络问题过一会儿还能重试，
                // openid 无效则是重试多少次都一样。
                val netFail = engine.netFail
                if (netFail) AreaStore.markNetFailed(oid) else AreaStore.markBadOpenid(oid)
                if (netFail && !silent) ui.post { showConnectFailed() }
                return false
            }
            engine.fetchRule()
            val list = engine.fetchAreas()
            if (engine.netFail) {
                // 失败：留着旧场地（如果还是同一个 openid），只记一笔冷却
                AreaStore.markNetFailed(oid)
                if (!silent) ui.post { showConnectFailed() }
                return false
            }
            AreaStore.publish(oid, list)
            ui.post { bindIfChanged() }
            return list.isNotEmpty()
        }
    }

    /**
     * 连不上服务器时的提示。
     *
     * 触发时机就是「连接并拉取场地」这一步 —— 原来的实现只往日志里写一行
     * 「网络失败」，界面上是个空场地列表，用户根本不知道发生了什么。
     */
    private fun showConnectFailed() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.conn_failed_title)
                .setMessage(R.string.conn_failed_msg)
                .setPositiveButton(R.string.ok, null)
                .show()
        } catch (_: Throwable) {
            toast(getString(R.string.conn_failed_toast))
        }
        scrollTop()
    }

    private fun scrollTop() {
        currentFocus?.clearFocus()
        rootScroll.post {
            rootScroll.scrollTo(0, 0)
            rootScroll.post { rootScroll.scrollTo(0, 0) }
        }
    }

    private fun doRun() {
        if (busy) return
        val oid = openid()
        if (oid.isEmpty()) {
            toast(getString(R.string.need_openid))
            return
        }
        val pace = RunEngine.parsePace(etPace.text.toString())
        if (pace == null || pace < 60) {
            toast(getString(R.string.bad_pace))
            return
        }
        val dist = etDist.text.toString().trim().toDoubleOrNull()
        if (dist == null || dist <= 0) {
            toast(getString(R.string.bad_dist))
            return
        }
        val tol = etTol.text.toString().trim().toDoubleOrNull()
        if (tol == null || tol < 0) {
            toast(getString(R.string.bad_tol))
            return
        }
        savePrefs()

        busy = true
        setEnabled(false)
        Thread {
            try {
                // 正常路径上 onResume 的 syncAreas() 已经把场地填好了，这里不会进。
                // 走到这里说明是"刚填完 openid 就直接点开始"之类的边角情况：
                // 库里有现成的就用，没有才真的打一次网络。
                // 用户主动点了按钮，所以这次失败要弹框（silent = false）。
                if (!AreaStore.hasAreasFor(oid)) fetchAreasFor(oid, silent = false)
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                ui.post {
                    busy = false
                    setEnabled(true)
                    // 先按库里的最新结果重绑一次，保证下拉框和 selectedItemPosition 对齐
                    bindIfChanged()
                    val area = areas.getOrNull(spArea.selectedItemPosition)
                    if (area == null) {
                        toast(getString(R.string.need_area))
                        return@post
                    }
                    RunController.start(
                        openid = oid,
                        area = area,
                        paceSecKm = pace,
                        targetM = dist * 1000.0,
                        noise = 0.6,
                        seed = (System.currentTimeMillis() % 100000).toInt(),
                        paceToleranceSec = tol
                    )
                    refreshRunButton()
                }
            }
        }.start()
    }

    private fun doStop() {
        RunController.cancel()
        toast(getString(R.string.stopping))
        refreshRunButton()
    }

    /** 开始 ↔ 结束，同一个按钮换脸。 */
    private fun refreshRunButton() {
        val running = RunController.running
        btnRun.setText(if (running) R.string.b_stop else R.string.b_run)
        btnRun.setBackgroundResource(
            if (running) R.drawable.btn_danger else R.drawable.btn_solid
        )
        btnRun.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.danger else R.color.on_accent)
        )
    }

    // ---------------------------------------------------------------- 仪表盘

    override fun onState(s: RunState) {
        ui.post {
            val pct = if (s.metersTotal > 0)
                (s.metersDone / s.metersTotal * 100).toInt().coerceIn(0, 100) else 0
            bar.progress = pct
            tvPercent.text = "$pct%"
            tvDistance.text = "已跑 %.2f / %.2f km".format(s.metersDone / 1000.0, s.metersTotal / 1000.0)

            val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"
            tvTime.text = "用时 ${RunEngine.fmtDuration(s.elapsedSec)}　剩余 $eta"

            val pace = if (s.paceSecKm > 0) RunEngine.fmtPace(s.paceSecKm) + "/km" else "--"
            val range = if (s.paceLo > 0 && s.paceHi > 0)
                "（分段 ${RunEngine.fmtPace(s.paceLo)}~${RunEngine.fmtPace(s.paceHi)}）" else ""
            tvPaceInfo.text = "配速 $pace$range　·　状态 ${s.phase}"

            tvBatches.text = "批次 ${s.batchesDone}/${s.batchesTotal}　·　点位 ${s.pointsDone}/${s.pointsTotal}"

            tvServer.text = if (s.serverMileage >= 0)
                "服务端累计 %.2f km / %s".format(s.serverMileage, RunEngine.fmtDuration(s.serverSeconds))
            else "服务端累计 --"
            tvServer.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        s.failed -> R.color.danger
                        s.finished -> R.color.ok
                        else -> R.color.accent
                    }
                )
            )

            refreshRunButton()
        }
    }

    // ---------------------------------------------------------------- 日志

    /** 主页不铺日志，只在摘要条里显示最后一行；完整日志在【日志】页。 */
    override fun log(line: String) {
        ui.post { tvLastLog.text = line }
    }

    private fun logStack(where: String, t: Throwable) {
        log("✗ $where 异常：${t.javaClass.name}: ${t.message}")
        t.stackTrace.take(8).forEach { log("      at $it") }
    }

    // ---------------------------------------------------------------- 后台保活

    /** 判断有没有进电池优化白名单。没进的话深度 Doze 会把后台冻住。 */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            true
        }
    }

    private fun updateBatteryHint() {
        val tv = findViewById<TextView>(R.id.tvBattery) ?: return
        val ok = isIgnoringBatteryOptimizations()
        tv.text = getString(if (ok) R.string.batt_ok_tip else R.string.batt_bad)
        tv.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger))
        // 整块底色也跟着红 / 绿，一眼能看出来
        tv.setBackgroundResource(if (ok) R.drawable.box_ok else R.drawable.box_bad)
        tv.setOnClickListener {
            if (ok) {
                toast(getString(R.string.batt_already_ok))
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.batt_title)
                    .setMessage(R.string.batt_msg)
                    .setPositiveButton(R.string.batt_go) { _, _ -> openBatterySettings() }
                    .setNegativeButton(R.string.dlg_cancel, null)
                    .show()
            }
        }
    }

    private fun openBatterySettings() {
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast(getString(R.string.batt_open_failed))
            }
        }
    }

    // ---------------------------------------------------------------- UI 工具

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    /**
     * 把场地铺进下拉框，并尽量保住用户已经选中的那个。
     *
     * @param keep 想保住的 placeCode。由调用方在替换 [areas] **之前**取好 ——
     *   否则每次场地刷新（比如从设置页连完回来）都会把用户刚选的场地重置掉。
     */
    private fun bindAreas(list: List<Area>, keep: String) {
        val names = if (list.isEmpty()) listOf(getString(R.string.need_area))
        else list.map { it.toString() }
        spArea.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )

        val idx = list.indexOfFirst { it.placeCode == keep }
        if (idx >= 0) spArea.setSelection(idx)
    }

    private fun setEnabled(on: Boolean) {
        etPace.isEnabled = on
        etDist.isEnabled = on
        etTol.isEnabled = on
        spArea.isEnabled = on
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("pace", etPace.text.toString().trim())
            .putString("dist", etDist.text.toString().trim())
            .putString("paceTol", etTol.text.toString().trim())
            .putString("placeCode", areas.getOrNull(spArea.selectedItemPosition)?.placeCode ?: "")
            .apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
