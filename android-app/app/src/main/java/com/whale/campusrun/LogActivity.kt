/*
 * 日志页：完整运行日志 + 导出 / 复制 / 清空。
 *
 * 主页不再铺日志（只显示最后一行摘要），完整日志都在这里。
 * 顶栏固定显示「日志」和返回箭头，与设置页一致。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity(), RunListener {

    private lateinit var logScroll: ScrollView
    private lateinit var tvLog: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logScroll = findViewById(R.id.logScroll)
        tvLog = findViewById(R.id.tvLog)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copy() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
            // 只清显示，单例里还留着最近 400 行 —— 和以前的行为保持一致
            toast(getString(R.string.log_cleared))
        }

        // 进来先铺一次全量快照
        render(RunController.logSnapshot())
    }

    override fun onStart() {
        super.onStart()
        RunController.addListener(this)
        render(RunController.logSnapshot())
    }

    override fun onStop() {
        RunController.removeListener(this)
        super.onStop()
    }

    private fun render(lines: List<String>) {
        tvLog.text = lines.joinToString("\n")
        scrollToBottom()
    }

    /** 日志滚到底。刻意绕开 fullScroll —— 它内部会 requestFocus，引起外层滚动。 */
    private fun scrollToBottom() {
        logScroll.post {
            val child = logScroll.getChildAt(0) ?: return@post
            logScroll.scrollTo(0, child.height)
        }
    }

    override fun log(line: String) {
        ui.post {
            tvLog.append(if (tvLog.text.isEmpty()) line else "\n$line")
            scrollToBottom()
        }
    }

    override fun onState(s: RunState) {
        // 日志页不显示仪表盘
    }

    private fun copy() {
        val text = tvLog.text.toString()
        if (text.isBlank()) {
            toast(getString(R.string.log_empty))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("campusrun.log", text))
        toast(getString(R.string.copied))
    }

    private fun share() {
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
                }, getString(R.string.b_share)
            )
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
