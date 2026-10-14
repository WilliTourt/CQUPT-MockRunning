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

    private var areas: List<Area> = emptyList()
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
    }

    private fun restoreOrInit() {
        if (RunController.running) {
            RunController.logFromUi("（已重新附着到正在进行的打卡）")
        }
    }

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
     * @return 是否拿到了可用场地
     */
    private fun connectAndFetch(oid: String): Boolean {
        if (!engine.connect(oid)) {
            if (engine.netFail) ui.post { showConnectFailed() }
            return false
        }
        engine.fetchRule()
        val list = engine.fetchAreas()
        ui.post {
            areas = list
            bindAreas(list)
        }
        if (engine.netFail) {
            ui.post { showConnectFailed() }
            return false
        }
        return list.isNotEmpty()
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
                // 还没拉过场地就先拉一次（openid 是在设置页填的）
                if (areas.isEmpty()) connectAndFetch(oid)
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                ui.post {
                    busy = false
                    setEnabled(true)
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

    private fun bindAreas(list: List<Area>) {
        val names = if (list.isEmpty()) listOf(getString(R.string.need_area)) else list.map { it.toString() }
        spArea.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        val lastCode = prefs.getString("placeCode", "")
        val idx = list.indexOfFirst { it.placeCode == lastCode }
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
