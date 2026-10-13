/*
 * 鲸鲸约跑 · 主页
 *
 * 主页只负责「打卡」。主题 / 统计 / 取码 / 说明 / 使用教程 / 开源仓库
 * 全部移到右上角「设置」页（SettingsActivity）。
 *
 * 跑步线程归 RunController（单例）所有，本 Activity 只是观察者。
 * 所以退出 App、锁屏、被系统重建 —— 进度和日志都能完整恢复。
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RunListener {

    private lateinit var etOpenid: EditText
    private lateinit var etPace: EditText
    private lateinit var etDist: EditText
    private lateinit var spArea: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnRun: Button

    private lateinit var segNatural: TextView
    private lateinit var segRealistic: TextView
    private lateinit var segSteady: TextView

    private lateinit var bar: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPaceInfo: TextView
    private lateinit var tvBatches: TextView
    private lateinit var tvServer: TextView

    private lateinit var logScroll: ScrollView
    private lateinit var tvLog: TextView
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
        etOpenid = findViewById(R.id.etOpenid)
        etPace = findViewById(R.id.etPace)
        etDist = findViewById(R.id.etDist)
        spArea = findViewById(R.id.spArea)
        btnConnect = findViewById(R.id.btnConnect)
        btnRun = findViewById(R.id.btnRun)

        segNatural = findViewById(R.id.segNatural)
        segRealistic = findViewById(R.id.segRealistic)
        segSteady = findViewById(R.id.segSteady)

        bar = findViewById(R.id.bar)
        tvPercent = findViewById(R.id.tvPercent)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvPaceInfo = findViewById(R.id.tvPaceInfo)
        tvBatches = findViewById(R.id.tvBatches)
        tvServer = findViewById(R.id.tvServer)

        logScroll = findViewById(R.id.logScroll)
        tvLog = findViewById(R.id.tvLog)

        etOpenid.setText(prefs.getString("openid", ""))
        etPace.setText(prefs.getString("pace", "5:45"))
        etDist.setText(prefs.getString("dist", "2.0"))

        btnConnect.setOnClickListener { doConnect() }
        btnRun.setOnClickListener { if (RunController.running) doStop() else doRun() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        segNatural.setOnClickListener { setPaceMode(TrackGen.PACE_NATURAL) }
        segRealistic.setOnClickListener { setPaceMode(TrackGen.PACE_REALISTIC) }
        segSteady.setOnClickListener { setPaceMode(TrackGen.PACE_STEADY) }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
            toast(getString(R.string.log_cleared))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLog() }

        updateBatteryHint()
        updatePaceModeUi()
        askNotificationPermission()
        restoreOrInit()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onStart() {
        super.onStart()
        RunController.addListener(this)
        // 重新附着：把单例里缓存的日志和状态整个捞回来
        val buf = RunController.logSnapshot()
        tvLog.text = if (buf.isEmpty()) "" else buf.joinToString("\n")
        scrollLogToBottom()
        onState(RunController.state)
        refreshRunButton()
        if (buf.isEmpty()) welcome()
    }

    override fun onStop() {
        RunController.removeListener(this)
        super.onStop()
    }

    /**
     * 从系统设置页返回时刷新电池白名单状态。
     * 只放在 onCreate 里的话，用户授权完回来还得重启 App 才变色。
     */
    override fun onResume() {
        super.onResume()
        updateBatteryHint()
    }

    private fun restoreOrInit() {
        if (RunController.running) {
            RunController.logFromUi("（已重新附着到正在进行的打卡）")
        }
    }

    // ---------------------------------------------------------------- 日志滚动

    /** 日志滚到底。刻意绕开 fullScroll —— 它内部会 requestFocus，引起外层滚动。 */
    private fun scrollLogToBottom() {
        logScroll.post {
            val child = logScroll.getChildAt(0) ?: return@post
            logScroll.scrollTo(0, child.height)
        }
    }

    private fun scrollTop() {
        currentFocus?.clearFocus()
        rootScroll.post {
            rootScroll.scrollTo(0, 0)
            rootScroll.post { rootScroll.scrollTo(0, 0) }
        }
    }

    // ---------------------------------------------------------------- 配速形态

    private fun setPaceMode(key: String) {
        prefs.edit().putString("paceMode", key).apply()
        updatePaceModeUi()
    }

    /** 选中的那块填色，其余透明 —— 和标签栏同一套分段控件样式。 */
    private fun updatePaceModeUi() {
        val cur = prefs.getString("paceMode", TrackGen.PACE_NATURAL)
        seg(segNatural, cur == TrackGen.PACE_NATURAL)
        seg(segRealistic, cur == TrackGen.PACE_REALISTIC)
        seg(segSteady, cur == TrackGen.PACE_STEADY)
    }

    private fun seg(t: TextView, active: Boolean) {
        if (active) {
            t.setBackgroundResource(R.drawable.seg_selected)
            t.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
            t.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            t.setBackgroundResource(R.drawable.seg_normal)
            t.setTextColor(ContextCompat.getColor(this, R.color.fg_dim))
            t.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    // ---------------------------------------------------------------- 打卡

    private fun welcome() {
        log("鲸鲸约跑 v${BuildConfig.VERSION_NAME} · 由 WilliTourt的蓝色大肥鱼 🐟 编写")
        log("")
        log("本程序不申请定位权限，也不会打开 GPS。")
        log("① 填 openid → 点【连接并拉取场地】；② 设好配速 → 点【开始打卡】。")
        log("开始之后就可以退出了：锁屏、切走、揣兜里都行，")
        log("有前台服务守着，通知栏能看到进度，回来也不会丢。")
        log("这段时间你就去操场上挨个摄像头刷脸。")
        log("")
        log("更多设置、统计、取码和教程在右上角【设置】里。")
    }

    private fun doConnect() {
        if (busy) return
        val openid = etOpenid.text.toString().trim()
        if (openid.isEmpty()) {
            toast(getString(R.string.need_openid))
            return
        }
        savePrefs()
        busy = true
        setEnabled(false)
        Thread {
            var netFail = false
            var got = 0
            try {
                if (engine.connect(openid)) {
                    engine.fetchRule()
                    val list = engine.fetchAreas()
                    netFail = engine.netFail
                    got = list.size
                    ui.post {
                        areas = list
                        bindAreas(list)
                    }
                } else {
                    netFail = engine.netFail
                }
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                ui.post {
                    busy = false
                    setEnabled(true)
                    when {
                        // 拉取场地时连不上服务器 —— 明确告诉用户，而不是静默留空列表
                        netFail -> showConnectFailed()
                        got == 0 -> toast(getString(R.string.conn_no_area))
                        else -> toast(getString(R.string.conn_ok))
                    }
                }
            }
        }.start()
    }

    /**
     * 连不上服务器时的提示。
     *
     * 触发时机就是「点连接并拉取场地」这一步 —— 原来的实现只往日志里写一行
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

    private fun doRun() {
        if (busy) return
        val openid = etOpenid.text.toString().trim()
        if (openid.isEmpty()) {
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
        val area = areas.getOrNull(spArea.selectedItemPosition)
        if (area == null) {
            toast(getString(R.string.need_area))
            return
        }
        savePrefs()
        runOnUiThread { tvLog.text = "" }

        RunController.start(
            openid = openid,
            area = area,
            paceSecKm = pace,
            targetM = dist * 1000.0,
            noise = 0.6,
            seed = (System.currentTimeMillis() % 100000).toInt(),
            profile = TrackGen.toProfile(prefs.getString("paceMode", TrackGen.PACE_NATURAL))
        )
        refreshRunButton()
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

    override fun log(line: String) {
        ui.post {
            tvLog.append(if (tvLog.text.isEmpty()) line else "\n$line")
            scrollLogToBottom()
        }
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
        spArea.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val lastCode = prefs.getString("placeCode", "")
        val idx = list.indexOfFirst { it.placeCode == lastCode }
        if (idx >= 0) spArea.setSelection(idx)
    }

    private fun setEnabled(on: Boolean) {
        btnConnect.isEnabled = on
        etOpenid.isEnabled = on
        etPace.isEnabled = on
        etDist.isEnabled = on
        spArea.isEnabled = on
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("openid", etOpenid.text.toString().trim())
            .putString("pace", etPace.text.toString().trim())
            .putString("dist", etDist.text.toString().trim())
            .putString("placeCode", areas.getOrNull(spArea.selectedItemPosition)?.placeCode ?: "")
            .apply()
    }

    private fun shareLog() {
        val text = tvLog.text.toString()
        if (text.isBlank()) {
            toast(getString(R.string.log_empty))
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "导出日志"
            )
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
