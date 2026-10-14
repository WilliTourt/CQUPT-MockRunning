/*
 * 设置页：账号（openid）/ 主题 / 成绩统计 / 取码 / 使用教程 / 说明信息 / 开源仓库。
 *
 * 主页只放打卡相关设置，其余全部收在这里。
 * 顶栏固定显示「设置」和返回箭头。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class SettingsActivity : AppCompatActivity() {

    companion object {
        /** 开源仓库。说明信息里也会显示这个地址。 */
        const val REPO_URL = "https://github.com/gufandf/CQUPT-MockRunning"
    }

    private lateinit var segAuto: TextView
    private lateinit var segDark: TextView
    private lateinit var segLight: TextView

    private lateinit var etOpenid: EditText
    private lateinit var btnConnect: Button

    private lateinit var scriptScroll: ScrollView
    private lateinit var tvScript: TextView

    private lateinit var btnRefreshStats: Button
    private lateinit var tvStatsSummary: TextView
    private lateinit var tvStatsByPlace: TextView
    private lateinit var tvStatsList: TextView

    /** 复用一份引擎用于连接与拉统计 —— 设置页不跑打卡。 */
    private val engine by lazy { RunEngine(Api(), object : RunListener {
        override fun log(line: String) {
            // 统计拉取的错误信息直接落在 tvStatsSummary 上，这里忽略
        }
        override fun onState(s: RunState) {
            // 设置页不显示仪表盘
        }
    }) }
    private val ui = Handler(Looper.getMainLooper())

    private var busy = false
    private var scriptText: String = ""

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        segAuto = findViewById(R.id.segThemeAuto)
        segDark = findViewById(R.id.segThemeDark)
        segLight = findViewById(R.id.segThemeLight)

        etOpenid = findViewById(R.id.etOpenid)
        btnConnect = findViewById(R.id.btnConnect)

        scriptScroll = findViewById(R.id.scriptScroll)
        tvScript = findViewById(R.id.tvScript)

        btnRefreshStats = findViewById(R.id.btnRefreshStats)
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
        tvStatsByPlace = findViewById(R.id.tvStatsByPlace)
        tvStatsList = findViewById(R.id.tvStatsList)

        etOpenid.setText(prefs.getString("openid", ""))
        // 边填边存：用户可能填完 openid 就直接返回主页，并不会点【连接并拉取场地】。
        // 不存的话主页读到的还是空的，场地当然不刷新 —— 这正是要修的那个 bug。
        // 主页 onResume 会用它自动拉一次场地，所以"填了就生效"。
        etOpenid.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString().orEmpty().trim()
                if (v != prefs.getString("openid", "")) {
                    prefs.edit().putString("openid", v).apply()
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnConnect.setOnClickListener { doConnect() }

        // openid 帮助折叠区
        val help = findViewById<View>(R.id.panelOpenidHelp)
        findViewById<Button>(R.id.btnToggleOpenidHelp).setOnClickListener {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        segAuto.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        segDark.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES) }
        segLight.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO) }

        btnRefreshStats.setOnClickListener { loadStats() }
        findViewById<Button>(R.id.btnCopyScript).setOnClickListener { copyScript() }
        findViewById<Button>(R.id.btnShareScript).setOnClickListener { shareScript() }
        findViewById<Button>(R.id.btnToggleScript).setOnClickListener {
            scriptScroll.visibility =
                if (scriptScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<TextView>(R.id.tvRepo).setOnClickListener { openRepo() }
        findViewById<TextView>(R.id.btnCopyRepo).setOnClickListener { copyRepo() }

        findViewById<TextView>(R.id.tvCredit).text =
            "鲸鲸约跑 v${BuildConfig.VERSION_NAME}\n由 WilliTourt的蓝色大肥鱼 🐟 编写"

        loadScript()
        updateThemeUi()
    }

    // ---------------------------------------------------------------- 账号

    /**
     * 连接并拉取场地。
     *
     * 场地的选择在主页做，所以这里连上之后把结果发布到 [AreaStore]，
     * 主页 onResume 一比对 revision 就会自动重绑下拉框 —— 不需要用户先去点一次
     * 【开始打卡】才看到场地（这就是「填完 openid 主页不刷新」的修复点）。
     * 这里同时给出明确的成功 / 失败反馈。
     */
    private fun doConnect() {
        if (busy) return
        val oid = etOpenid.text.toString().trim()
        if (oid.isEmpty()) {
            toast(getString(R.string.need_openid))
            return
        }
        // 换了 openid：旧场地属于上一个账号，先作废，免得主页显示别人的场地
        if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
            AreaStore.invalidate()
        }
        prefs.edit().putString("openid", oid).apply()
        busy = true
        btnConnect.isEnabled = false
        val old = btnConnect.text
        btnConnect.text = getString(R.string.conn_connecting)

        Thread {
            // 结果分三类：连不上 / openid 有问题 / 拿到场地（可能一个都没有）
            var netFail = false
            var badOpenid = false
            var got = 0
            try {
                if (engine.connect(oid)) {
                    engine.fetchRule()
                    val list = engine.fetchAreas()
                    netFail = engine.netFail
                    got = list.size
                    if (netFail) AreaStore.markNetFailed(oid) else AreaStore.publish(oid, list)
                } else {
                    netFail = engine.netFail
                    badOpenid = !netFail
                    if (netFail) AreaStore.markNetFailed(oid) else AreaStore.markBadOpenid(oid)
                }
            } catch (t: Throwable) {
                // 异常同样按失败处理，不让界面卡在"连接中"
                AreaStore.markNetFailed(oid)
                netFail = true
            } finally {
                ui.post {
                    busy = false
                    btnConnect.isEnabled = true
                    btnConnect.text = old
                    when {
                        netFail -> showConnectFailed()
                        badOpenid -> toast(getString(R.string.conn_bad_openid))
                        got == 0 -> toast(getString(R.string.conn_no_area))
                        else -> toast(getString(R.string.conn_ok))
                    }
                }
            }
        }.start()
    }

    /** 连不上服务器时，和主页用同一个对话框，措辞保持一致。 */
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
    }

    // ---------------------------------------------------------------- 主题

    /**
     * 切换主题。
     *
     * 注意：这里改完 AppCompat 会重建 Activity，本页会重新走一遍 onCreate，
     * 分段控件的选中态靠 [updateThemeUi] 在重建后重新读 prefs 恢复。
     */
    private fun applyThemeMode(mode: Int) {
        prefs.edit().putInt("themeMode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        updateThemeUi()
    }

    private fun currentTheme(): Int =
        prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun updateThemeUi() {
        val cur = currentTheme()
        seg(segAuto, cur == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        seg(segDark, cur == AppCompatDelegate.MODE_NIGHT_YES)
        seg(segLight, cur == AppCompatDelegate.MODE_NIGHT_NO)
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

    // ---------------------------------------------------------------- 统计

    /**
     * 拉成绩。
     *
     * 成绩接口要 token，而 token 是靠 openid 现换的。设置页自建了一份 Api
     * （主页那份的 token 拿不到），所以这里先用存下来的 openid 换一次 token，
     * 换不到就说明是网络问题，直接给出和主页一致的提示。
     */
    private fun loadStats() {
        if (busy) return
        val openid = prefs.getString("openid", "").orEmpty().trim()
        if (openid.isEmpty()) {
            tvStatsSummary.text = getString(R.string.stats_need_openid)
            return
        }
        busy = true
        btnRefreshStats.isEnabled = false
        tvStatsSummary.text = getString(R.string.stats_loading)
        Thread {
            try {
                val tokenOk = engine.connect(openid)
                if (!tokenOk) {
                    ui.post {
                        busy = false
                        btnRefreshStats.isEnabled = true
                        if (engine.netFail) {
                            tvStatsSummary.text = getString(R.string.conn_failed_title)
                            showConnectFailed()
                        } else {
                            tvStatsSummary.text = getString(R.string.stats_bad_openid)
                        }
                    }
                    return@Thread
                }
                val s = engine.fetchStats()
                ui.post {
                    busy = false
                    btnRefreshStats.isEnabled = true
                    if (s == null) tvStatsSummary.text = getString(R.string.stats_failed)
                    else renderStats(s)
                }
            } catch (t: Throwable) {
                ui.post {
                    busy = false
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
        tvStatsByPlace.text = if (s.byPlace.isEmpty()) getString(R.string.stats_dash)
        else s.byPlace.joinToString("\n") { "${it.first}　${it.second} 次" }

        tvStatsList.text = if (s.rows.isEmpty()) getString(R.string.stats_dash)
        else s.rows.joinToString("\n") { r ->
            val mark = if (r.valid) "✓" else "✗"
            val extra = if (r.valid) "" else "  ${r.reason}"
            "%s %-8s %5.2fkm %8s%s".format(mark, r.placeName, r.distanceKm, r.timeText, extra)
        }
    }

    // ---------------------------------------------------------------- 取码

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
            // 文件方式失败，退化成纯文本
        }

        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "get_openid.py")
            putExtra(Intent.EXTRA_TEXT, scriptText)
        }
        startActivity(Intent.createChooser(i, "分享脚本"))
    }

    // ---------------------------------------------------------------- 开源仓库

    /** 点开仓库：有浏览器就用浏览器，没有就把地址复制到剪贴板。 */
    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (t: Throwable) {
            copyRepo()
            toast(getString(R.string.repo_open_failed))
        }
    }

    private fun copyRepo() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("repo", REPO_URL))
        toast(getString(R.string.repo_copied))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
