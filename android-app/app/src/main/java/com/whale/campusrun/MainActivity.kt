/*
 * 游鲸 · 主界面
 *
 * 跑步线程归 RunController（单例）所有，本 Activity 只是观察者。
 * 所以退出 App、锁屏、被系统重建 —— 进度和日志都能完整恢复。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity(), RunListener {

    private lateinit var etOpenid: EditText
    private lateinit var etPace: EditText
    private lateinit var etDist: EditText
    private lateinit var spArea: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnRun: Button

    private lateinit var bar: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvPaceInfo: TextView
    private lateinit var tvBatches: TextView
    private lateinit var tvServer: TextView

    private lateinit var logScroll: ScrollView
    private lateinit var tvLog: TextView

    private lateinit var tabRun: TextView
    private lateinit var tabStats: TextView
    private lateinit var tabHelp: TextView
    private lateinit var panelRun: View
    private lateinit var panelStats: View
    private lateinit var panelHelp: View
    private lateinit var scriptScroll: ScrollView
    private lateinit var tvScript: TextView
    private lateinit var btnRefreshStats: Button
    private lateinit var tvStatsSummary: TextView
    private lateinit var tvStatsByPlace: TextView
    private lateinit var tvStatsList: TextView

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

        bar = findViewById(R.id.bar)
        tvPercent = findViewById(R.id.tvPercent)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvPaceInfo = findViewById(R.id.tvPaceInfo)
        tvBatches = findViewById(R.id.tvBatches)
        tvServer = findViewById(R.id.tvServer)

        logScroll = findViewById(R.id.logScroll)
        tvLog = findViewById(R.id.tvLog)

        tabRun = findViewById(R.id.tabRun)
        tabStats = findViewById(R.id.tabStats)
        tabHelp = findViewById(R.id.tabHelp)
        panelRun = findViewById(R.id.panelRun)
        panelStats = findViewById(R.id.panelStats)
        panelHelp = findViewById(R.id.panelHelp)
        scriptScroll = findViewById(R.id.scriptScroll)
        tvScript = findViewById(R.id.tvScript)
        btnRefreshStats = findViewById(R.id.btnRefreshStats)
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
        tvStatsByPlace = findViewById(R.id.tvStatsByPlace)
        tvStatsList = findViewById(R.id.tvStatsList)

        etOpenid.setText(prefs.getString("openid", ""))
        etPace.setText(prefs.getString("pace", "5:45"))
        etDist.setText(prefs.getString("dist", "2.0"))

        btnConnect.setOnClickListener { doConnect() }
        btnRun.setOnClickListener { if (RunController.running) doStop() else doRun() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
            toast("只清了显示，RunController 里还留着最近 400 行")
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareLog() }
        tabRun.setOnClickListener { showTab(0) }
        tabStats.setOnClickListener { showTab(1) }
        tabHelp.setOnClickListener { showTab(2) }
        findViewById<Button>(R.id.btnCopyScript).setOnClickListener { copyScript() }
        findViewById<Button>(R.id.btnShareScript).setOnClickListener { shareScript() }
        findViewById<Button>(R.id.btnToggleScript).setOnClickListener {
            scriptScroll.visibility = if (scriptScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        loadScript()
        btnRefreshStats.setOnClickListener { loadStats() }
        findViewById<TextView>(R.id.tvTheme).setOnClickListener { cycleTheme() }
        updateThemeLabel()
        updateBatteryHint()

        findViewById<TextView>(R.id.tvCredit).text =
            "鲸鲸约跑 v${BuildConfig.VERSION_NAME}\n由 WilliTourt的蓝色大肥鱼 🐟 编写"

        askNotificationPermission()
        showTab(0)
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

    // ---------------------------------------------------------------- 主题

    /** 自动 → 深色 → 浅色 → 自动，循环。改完 AppCompat 会自己重建界面。 */
    private fun cycleTheme() {
        val cur = prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val next = when (cur) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        prefs.edit().putInt("themeMode", next).apply()
        AppCompatDelegate.setDefaultNightMode(next)
    }

    private fun updateThemeLabel() {
        val mode = prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val tv = findViewById<TextView>(R.id.tvTheme)
        tv.text = when (mode) {
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
            else -> getString(R.string.theme_auto)
        }
    }

    // ---------------------------------------------------------------- 标签页

    private fun showTab(idx: Int) {
        panelRun.visibility = if (idx == 0) View.VISIBLE else View.GONE
        panelStats.visibility = if (idx == 1) View.VISIBLE else View.GONE
        panelHelp.visibility = if (idx == 2) View.VISIBLE else View.GONE
        seg(tabRun, idx == 0)
        seg(tabStats, idx == 1)
        seg(tabHelp, idx == 2)

        // 这里刻意不用 fullScroll：它内部会 requestFocus，
        // 焦点一旦落到下面的控件上（比如日志框），外层 ScrollView 会自动滚过去，
        // 表现就是"点打卡标签，页面莫名往下瞬移一截"。
        currentFocus?.clearFocus()
        rootScroll.post {
            rootScroll.scrollTo(0, 0)
            rootScroll.post { rootScroll.scrollTo(0, 0) }
        }
    }

    /** 日志滚到底。同样绕开 fullScroll 的焦点副作用。 */
    private fun scrollLogToBottom() {
        logScroll.post {
            val child = logScroll.getChildAt(0) ?: return@post
            logScroll.scrollTo(0, child.height)
        }
    }

    /** 分段控件的选中样式：选中的那块填色，另一块透明。 */
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
        log("① 填 openid → 点【连接】；② 设好配速 → 点【开始打卡】。")
        log("开始之后就可以退出了：锁屏、切走、揣兜里都行，")
        log("有前台服务守着，通知栏能看到进度，回来也不会丢。")
        log("这段时间你就去操场上挨个摄像头刷脸。")
    }

    private fun doConnect() {
        if (busy) return
        val openid = etOpenid.text.toString().trim()
        if (openid.isEmpty()) {
            toast("先填 openid")
            return
        }
        savePrefs()
        busy = true
        setEnabled(false)
        Thread {
            try {
                if (engine.connect(openid)) {
                    engine.fetchRule()
                    val list = engine.fetchAreas()
                    ui.post {
                        areas = list
                        bindAreas(list)
                    }
                }
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                ui.post {
                    busy = false
                    setEnabled(true)
                }
            }
        }.start()
    }

    private fun doRun() {
        if (busy) return
        val openid = etOpenid.text.toString().trim()
        if (openid.isEmpty()) {
            toast("先填 openid")
            return
        }
        val pace = RunEngine.parsePace(etPace.text.toString())
        if (pace == null || pace < 60) {
            toast("配速格式不对，示例 5:45")
            return
        }
        val dist = etDist.text.toString().trim().toDoubleOrNull()
        if (dist == null || dist <= 0) {
            toast("距离格式不对，示例 2.0")
            return
        }
        val area = areas.getOrNull(spArea.selectedItemPosition)
        if (area == null) {
            toast("还没有场地，先点【连接】")
            return
        }
        savePrefs()
        showTab(0)
        runOnUiThread { tvLog.text = "" }

        RunController.start(
            openid = openid,
            area = area,
            paceSecKm = pace,
            targetM = dist * 1000.0,
            noise = 0.6,
            seed = (System.currentTimeMillis() % 100000).toInt()
        )
        refreshRunButton()
    }

    private fun doStop() {
        RunController.cancel()
        toast("正在停止，当前批次传完就停")
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

    // ---------------------------------------------------------------- 统计

    private fun loadStats() {
        if (busy) return
        btnRefreshStats.isEnabled = false
        tvStatsSummary.text = "拉取中…"
        Thread {
            try {
                val s = engine.fetchStats()
                ui.post {
                    btnRefreshStats.isEnabled = true
                    if (s == null) tvStatsSummary.text = "拉取失败，重试一次"
                    else renderStats(s)
                }
            } catch (t: Throwable) {
                logStack("统计", t)
                ui.post {
                    btnRefreshStats.isEnabled = true
                    tvStatsSummary.text = "拉取异常：${t.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun renderStats(s: Stats) {
        tvStatsSummary.text = buildString {
            append("有效次数　").append(s.validCount).append(" 次\n")
            append("无效次数　").append(s.invalidCount).append(" 次\n")
            append("累计里程　").append("%.2f".format(s.totalKm)).append(" km\n")
            append("平均配速　")
            append(if (s.avgPaceSecKm > 0) RunEngine.fmtPace(s.avgPaceSecKm) + "/km" else "--")
            append("\n全部记录　").append(s.total).append(" 条")
        }
        tvStatsByPlace.text = if (s.byPlace.isEmpty()) "—"
        else s.byPlace.joinToString("\n") { "${it.first}　${it.second} 次" }

        tvStatsList.text = if (s.rows.isEmpty()) "—"
        else s.rows.joinToString("\n") { r ->
            val mark = if (r.valid) "✓" else "✗"
            val extra = if (r.valid) "" else "  ${r.reason}"
            "%s %-8s %5.2fkm %8s%s".format(mark, r.placeName, r.distanceKm, r.timeText, extra)
        }
    }

    // ---------------------------------------------------------------- 仪表盘

    private fun resetDash() {
        bar.progress = 0
        tvPercent.text = "0%"
        tvDistance.text = getString(R.string.dash_idle_distance)
        tvTime.text = getString(R.string.dash_idle_time)
        tvPaceInfo.text = getString(R.string.dash_idle_pace)
        tvBatches.text = getString(R.string.dash_idle_batch)
        tvServer.text = getString(R.string.dash_idle_server)
    }

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
            tvPaceInfo.text = "配速 $pace　·　状态 ${s.phase}"

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
            if (!RunController.running && s.phase != "空闲" && !busy) resetDashIfIdle()
        }
    }

    private fun resetDashIfIdle() {
        // 跑完或失败后按钮要变回"开始打卡"，但仪表盘保留最后的数字给用户看
        refreshRunButton()
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

    // ---------------------------------------------------------------- 取码页

    private var scriptText: String = ""

    /** 从 assets 读提取脚本并原样展示，可以复制或分享到电脑。 */
    private fun loadScript() {
        try {
            scriptText = assets.open("get_openid.py")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            scriptText = "# 脚本读取失败：" + t.javaClass.simpleName + ": " + t.message
        }
        tvScript.text = scriptText
        findViewById<TextView>(R.id.tvScriptPath).text =
            "共 " + scriptText.lines().size + " 行 / " + scriptText.length + " 字符\n" +
                    "存成 get_openid.py，双击运行即可（电脑记得先装 Python 3）"
        scriptScroll.post { scriptScroll.scrollTo(0, 0) }
    }

    private fun copyScript() {
        if (scriptText.isEmpty()) loadScript()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("get_openid.py", scriptText))
        toast(getString(R.string.copied))
    }

    /**
     * 分享脚本。
     *
     * 优先当**独立文件**发出去（写到 cache/share/get_openid.py，用 FileProvider 授权），
     * 这样发到微信「文件传输助手」之后电脑上就是一个能直接双击的 .py，
     * 不用从聊天记录里复制粘贴。文件方式失败再退化成纯文本。
     */
    private fun shareScript() {
        if (scriptText.isEmpty()) loadScript()

        try {
            val dir = File(cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "get_openid.py")
            f.writeText(scriptText, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "get_openid.py")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "分享 get_openid.py"))
            return
        } catch (t: Throwable) {
            log("脚本文件分享失败（${t.javaClass.simpleName}），改用纯文本")
        }

        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "get_openid.py")
            putExtra(Intent.EXTRA_TEXT, scriptText)
        }
        startActivity(Intent.createChooser(i, "分享脚本"))
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
        val tv = findViewById<TextView>(R.id.tvBattery)
        val ok = isIgnoringBatteryOptimizations()
        tv.text = getString(if (ok) R.string.batt_ok_tip else R.string.batt_bad)
        tv.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger))
        // 整块底色也跟着红 / 绿，一眼能看出来
        tv.setBackgroundResource(if (ok) R.drawable.box_ok else R.drawable.box_bad)
        tv.setOnClickListener {
            if (ok) {
                toast("已经是白名单状态了")
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
                toast("打开设置失败，手动去：设置 → 应用 → 电池 → 不受限制")
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
        val names = if (list.isEmpty()) listOf("（无可用场地，先点【连接】）") else list.map { it.toString() }
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
            toast("日志是空的")
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
