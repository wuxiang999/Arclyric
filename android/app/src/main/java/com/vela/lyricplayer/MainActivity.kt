package com.vela.lyricplayer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val REQ_IMPORT = 1001
        private const val REQ_NOTIFICATION = 1002
    }

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var accessButton: Button
    private lateinit var statusDot: View
    private lateinit var statusPill: TextView
    private lateinit var accessState: TextView
    private lateinit var serviceState: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowArtist: TextView
    private lateinit var lyricsState: TextView
    private lateinit var toggleButton: Button
    private lateinit var versionChip: TextView

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ticker.postDelayed(this, 2000L)
        }
    }

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        accessButton = findViewById(R.id.btnAccess)
        statusDot = findViewById(R.id.statusDot)
        statusPill = findViewById(R.id.statusPill)
        accessState = findViewById(R.id.accessState)
        serviceState = findViewById(R.id.serviceState)
        nowTitle = findViewById(R.id.nowTitle)
        nowArtist = findViewById(R.id.nowArtist)
        lyricsState = findViewById(R.id.lyricsState)
        toggleButton = findViewById(R.id.btnToggle)
        versionChip = findViewById(R.id.versionChip)

        versionChip.text = "v" + appVersion()

        // 日志区是嵌在外层 ScrollView 里的内层滚动列表。
        // 不拦一下的话上下滑动会被外层吃掉，日志永远只能看最新几行、翻不上去。
        logScroll.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        accessButton.setOnClickListener { openAccessSettings() }
        toggleButton.setOnClickListener { if (PlayerService.running) stopSync() else startSync() }
        findViewById<View>(R.id.btnImport).setOnClickListener { importLyrics() }
        findViewById<View>(R.id.btnResend).setOnClickListener { resendLyrics() }
        findViewById<View>(R.id.btnLibrary).setOnClickListener {
            try {
                startActivity(Intent(this, LyricsActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "打开歌词库失败：" + (e.message ?: e.toString()), Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnClear).setOnClickListener { logText.text = "" }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyLog() }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareLog() }

        LogBus.add(logListener)
        LogBus.log("弧光歌词手机端已启动")
        refreshStatus()
        autoStart()
    }

    // 打开应用即自动开始同步，手环端的运行日志会持续回传到这里
    private fun autoStart() {
        if (!MediaWatcher.hasAccess(this)) {
            LogBus.log("未开启通知使用权，自动同步暂未启动")
            return
        }
        if (PlayerService.running) {
            return
        }
        requestNotificationPermission()
        try {
            PlayerService.start(this)
            LogBus.log("已自动启动同步服务，等待手环日志…")
        } catch (e: Exception) {
            LogBus.log("自动启动失败：" + (e.message ?: e.toString()))
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 2000L)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        LogBus.remove(logListener)
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun appVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun refreshStatus() {
        val access = MediaWatcher.hasAccess(this)
        val running = PlayerService.running

        when {
            !access -> {
                statusPill.text = "等待授权"
                statusPill.setTextColor(getColor(R.color.warn))
                statusDot.setBackgroundResource(R.drawable.dot_warn)
            }
            running -> {
                statusPill.text = "同步中"
                statusPill.setTextColor(getColor(R.color.accent))
                statusDot.setBackgroundResource(R.drawable.dot_ok)
            }
            else -> {
                statusPill.text = "未启动"
                statusPill.setTextColor(getColor(R.color.text_muted))
                statusDot.setBackgroundResource(R.drawable.dot_off)
            }
        }

        accessState.text = if (access) "已授予" else "未授予"
        accessState.setTextColor(getColor(if (access) R.color.accent else R.color.danger))

        serviceState.text = if (running) "运行中" else "已停止"
        serviceState.setTextColor(getColor(if (running) R.color.accent else R.color.text_muted))

        accessButton.text = if (access) "通知使用权已开启" else "去开启通知使用权"

        if (running) {
            toggleButton.text = "停止同步"
            toggleButton.setBackgroundResource(R.drawable.bg_ghost)
            toggleButton.setTextColor(getColor(R.color.text_primary))
        } else {
            toggleButton.text = "启动同步"
            toggleButton.setBackgroundResource(R.drawable.bg_primary)
            toggleButton.setTextColor(getColor(R.color.accent_deep))
        }

        val now = try {
            if (access) MediaWatcher.snapshot(this) else null
        } catch (e: Exception) {
            null
        }
        if (now != null && now.title.isNotBlank()) {
            nowTitle.text = now.title
            nowArtist.text = if (now.artist.isBlank()) "未知歌手" else now.artist
            nowTitle.setTextColor(getColor(R.color.text_primary))
        } else {
            nowTitle.text = "未在播放"
            nowArtist.text = "在手机上打开音乐，手环会自动跟随"
            nowTitle.setTextColor(getColor(R.color.text_muted))
        }

        val lyric = if (now != null && SyncStatus.nowTitle == now.title) SyncStatus.lyricsState else ""
        when {
            lyric.isBlank() -> {
                lyricsState.text = "歌词：—"
                lyricsState.setTextColor(getColor(R.color.text_muted))
            }
            lyric.contains("匹配中") -> {
                lyricsState.text = lyric
                lyricsState.setTextColor(getColor(R.color.warn))
            }
            lyric.contains("未匹配") -> {
                lyricsState.text = lyric
                lyricsState.setTextColor(getColor(R.color.text_muted))
            }
            else -> {
                lyricsState.text = lyric
                lyricsState.setTextColor(getColor(R.color.accent))
            }
        }
    }

    private fun openAccessSettings() {        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开通知使用权设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSync() {
        if (!MediaWatcher.hasAccess(this)) {
            Toast.makeText(this, "请先开启通知使用权", Toast.LENGTH_SHORT).show()
            openAccessSettings()
            return
        }
        requestNotificationPermission()
        PlayerService.start(this)
        Toast.makeText(this, "同步服务已启动", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun stopSync() {
        PlayerService.stop(this)
        Toast.makeText(this, "同步服务已停止", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
    }

    private fun importLyrics() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resendLyrics() {
        if (!PlayerService.running) {
            Toast.makeText(this, "请先启动同步服务", Toast.LENGTH_SHORT).show()
            return
        }
        PlayerService.resend(this)
        Toast.makeText(this, "已请求重发歌词", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) {
            return
        }
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(index).uri)
            }
        }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            return
        }
        val repository = LyricsRepository(this)
        var success = 0
        uris.forEach { uri ->
            if (repository.import(uri) != null) {
                success++
            }
        }
        Toast.makeText(this, "已导入 $success 个歌词文件", Toast.LENGTH_SHORT).show()
        if (PlayerService.running) {
            PlayerService.resend(this)
        }
    }

    private fun copyLog() {
        try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("弧光歌词日志", logText.text.toString()))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败：" + (e.message ?: e.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLog() {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "弧光歌词运行日志")
                putExtra(Intent.EXTRA_TEXT, logText.text.toString())
            }
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: Exception) {
            copyLog()
        }
    }

    private fun appendLog(line: String) {
        // 用户翻到上面看历史日志时不要再把他拽回底部
        val atBottom = !logScroll.canScrollVertically(1)
        logText.append(line)
        logText.append("\n")
        if (logText.length() > 12000) {
            val value = logText.text.toString()
            val cut = value.lastIndexOf('\n', value.length - 6000)
            if (cut > 0) {
                logText.text = value.substring(cut + 1)
            }
        }
        if (atBottom) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
