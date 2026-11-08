package com.vela.lyricplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * 歌词库：默认展示手环端词库（手环是唯一真源），另一页是手机本地缓存。
 *
 * 手环词库通过桥接分片回传，这里只做展示 / 删除 / 补推；
 * 本地缓存页才是导入 LRC、按网易云歌单批量抓词的入口。
 */
class LyricsActivity : Activity() {
    companion object {
        private const val REQ_IMPORT = 2001
        // 一次导入最多处理多少首：太多会等很久，也容易触发接口限流
        private const val MAX_IMPORT = 60
        private const val MODE_BAND = 0
        private const val MODE_LOCAL = 1
    }

    private lateinit var repository: LyricsRepository
    private lateinit var listContainer: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var progressView: TextView
    private lateinit var playlistInput: EditText
    private lateinit var importButton: Button
    private lateinit var importCard: View
    private lateinit var toolRow: View
    private lateinit var tabBand: TextView
    private lateinit var tabLocal: TextView
    private lateinit var bandHint: TextView
    private lateinit var btnRefreshBand: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var importing = false
    private var mode = MODE_BAND

    // 手环词库有新快照时刷新列表
    private val bandListener: () -> Unit = {
        handler.post { if (mode == MODE_BAND) render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)
        repository = LyricsRepository(this)

        listContainer = findViewById(R.id.listContainer)
        listScroll = findViewById(R.id.listScroll)
        emptyView = findViewById(R.id.emptyView)
        countView = findViewById(R.id.countView)
        progressView = findViewById(R.id.progressView)
        playlistInput = findViewById(R.id.etPlaylist)
        importButton = findViewById(R.id.btnImportPlaylist)
        importCard = findViewById(R.id.importCard)
        toolRow = findViewById(R.id.toolRow)
        tabBand = findViewById(R.id.tabBand)
        tabLocal = findViewById(R.id.tabLocal)
        bandHint = findViewById(R.id.bandHint)
        btnRefreshBand = findViewById(R.id.btnRefreshBand)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        importButton.setOnClickListener { importPlaylist() }
        findViewById<View>(R.id.btnImportLrc).setOnClickListener { pickLrc() }
        findViewById<View>(R.id.btnPushAll).setOnClickListener { pushCurrent() }
        tabBand.setOnClickListener { switchMode(MODE_BAND) }
        tabLocal.setOnClickListener { switchMode(MODE_LOCAL) }
        bandHint.setOnClickListener { refreshBand() }
        btnRefreshBand.setOnClickListener {
            toast("正在向手环请求词库…")
            refreshBand()
        }

        BandLibrary.addListener(bandListener)
        switchMode(MODE_BAND)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (mode == MODE_BAND) {
            refreshBand()
            handler.removeCallbacks(bandPoll)
            handler.postDelayed(bandPoll, 3000L)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(bandPoll)
        super.onPause()
    }

    override fun onDestroy() {
        BandLibrary.removeListener(bandListener)
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- 视图切换 ----------

    private fun switchMode(next: Int) {
        mode = next
        val band = next == MODE_BAND
        tabBand.setTextColor(getColor(if (band) R.color.accent_deep else R.color.text_secondary))
        tabBand.setBackgroundResource(if (band) R.drawable.bg_primary else R.drawable.bg_chip)
        tabLocal.setTextColor(getColor(if (band) R.color.text_secondary else R.color.accent_deep))
        tabLocal.setBackgroundResource(if (band) R.drawable.bg_chip else R.drawable.bg_primary)
        // 导入只写手机缓存，手环页不需要它占位置
        importCard.visibility = if (band) View.GONE else View.VISIBLE
        toolRow.visibility = if (band) View.GONE else View.VISIBLE
        bandHint.text = if (band) "刷新" else ""
        btnRefreshBand.visibility = if (band) View.VISIBLE else View.GONE
        progressView.visibility = if (band) View.GONE else progressView.visibility
        render()
        if (band) {
            refreshBand()
            handler.removeCallbacks(bandPoll)
            handler.post(bandPoll)
        } else {
            handler.removeCallbacks(bandPoll)
        }
    }

    private fun refreshBand() {
        PlayerService.start(this)
        PlayerService.requestLibrary(this)
    }

    // 手环不在线时界面会一直是空的，这里低频自动补问，免得用户找不到按钮
    private val bandPoll = object : Runnable {
        override fun run() {
            if (mode == MODE_BAND && !isFinishing) {
                val stale = BandLibrary.items.isEmpty() ||
                    System.currentTimeMillis() - BandLibrary.updatedAt > 30000L
                if (stale) {
                    PlayerService.requestLibrary(this@LyricsActivity)
                }
                handler.postDelayed(this, 5000L)
            }
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (mode == MODE_BAND) {
            renderBand()
        } else {
            renderLocal()
        }
    }

    // ---------- 手环词库（镜像） ----------

    private fun renderBand() {
        val items = BandLibrary.items
        countView.text = if (items.isEmpty()) "等待手环" else "手环 ${items.size} 首"
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = "还没有拿到手环词库\n请确认手环与手机已连接，然后点右上角「刷新」"
        listScroll.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_lyric, listContainer, false)
            row.findViewById<TextView>(R.id.rowTitle).text = item.title.ifEmpty { item.key }
            row.findViewById<TextView>(R.id.rowMeta).text = buildString {
                append(item.artist.ifEmpty { "未知歌手" })
                append(" · ")
                append(item.lines)
                append(" 行")
            }
            val local = localFile(item.key)
            row.findViewById<View>(R.id.rowSend).visibility =
                if (local != null) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.rowSend).setOnClickListener {
                if (local != null) {
                    send(local, item.title)
                }
            }
            row.findViewById<View>(R.id.rowDelete).setOnClickListener {
                confirmDeleteOnBand(item, local)
            }
            row.setOnClickListener { previewBand(item, local) }
            listContainer.addView(row)
        }
    }

    private fun localFile(key: String): File? {
        return try {
            repository.list().firstOrNull { it.name == key }
        } catch (e: Exception) {
            null
        }
    }

    private fun previewBand(item: BandLibrary.Item, local: File?) {
        if (local == null) {
            AlertDialog.Builder(this)
                .setTitle(item.title.ifEmpty { item.key })
                .setMessage("这一首只在手环上，手机里没有缓存。\n播放这首歌时手机会自动匹配，匹配到后就能在这里预览和补推。")
                .setPositiveButton("知道了", null)
                .setNeutralButton("从手环删除") { _, _ -> confirmDeleteOnBand(item, null) }
                .show()
            return
        }
        val content = repository.read(local)
        showPreview(item.title.ifEmpty { local.name }, item.artist, content) {
            send(local, item.title)
        }
    }

    private fun confirmDeleteOnBand(item: BandLibrary.Item, local: File?) {
        AlertDialog.Builder(this)
            .setTitle("从手环删除")
            .setMessage("确定删除《${item.title.ifEmpty { item.key }}》？删除后手环词库和手机缓存都不会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (local != null) {
                    repository.delete(local.name)
                }
                PlayerService.deleteFromBand(this, item.key)
                toast("已请求手环删除")
                handler.postDelayed({ refreshBand() }, 1500L)
                render()
            }
            .show()
    }

    // ---------- 手机缓存 ----------

    private fun renderLocal() {
        val files = try {
            repository.list()
        } catch (e: Exception) {
            emptyList<File>()
        }
        countView.text = "缓存 ${files.size} 首"
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = "手机缓存是空的\n导入 LRC 或从网易云歌单批量导入"
        listScroll.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (file in files) {
            val (title, artist) = repository.label(file)
            val lines = repository.lineCount(file)
            val row = inflater.inflate(R.layout.item_lyric, listContainer, false)
            row.findViewById<TextView>(R.id.rowTitle).text = title.ifEmpty { file.name }
            row.findViewById<TextView>(R.id.rowMeta).text = buildString {
                append(artist.ifEmpty { "未知歌手" })
                append(" · ")
                append(lines)
                append(" 行")
            }
            row.findViewById<View>(R.id.rowSend).visibility = View.VISIBLE
            row.setOnClickListener { preview(file, title, artist) }
            row.findViewById<View>(R.id.rowSend).setOnClickListener { send(file, title) }
            row.findViewById<View>(R.id.rowDelete).setOnClickListener { confirmDelete(file, title) }
            listContainer.addView(row)
        }
    }

    private fun preview(file: File, title: String, artist: String) {
        val content = repository.read(file)
        if (content.isBlank()) {
            toast("这个歌词文件是空的")
            return
        }
        showPreview(title, artist, content) { send(file, title) }
    }

    private fun showPreview(title: String, artist: String, content: String, onSend: () -> Unit) {
        val body = TextView(this).apply {
            text = content
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setLineSpacing(6f, 1f)
            setPadding(46, 34, 46, 10)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(if (artist.isBlank()) title else "$title · $artist")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .setNeutralButton("发送到手环") { _, _ -> onSend() }
            .show()
    }

    private fun send(file: File, title: String) {
        try {
            PlayerService.start(this)
            PlayerService.pushFile(this, file.name)
            toast("已发送《$title》到手环")
            LogBus.log("手动推送歌词：${file.name}")
            handler.postDelayed({ refreshBand() }, 2000L)
        } catch (e: Exception) {
            toast("发送失败：" + (e.message ?: e.toString()))
        }
    }

    private fun pushCurrent() {
        try {
            PlayerService.start(this)
            PlayerService.resend(this)
            toast("已请求重发当前歌词")
        } catch (e: Exception) {
            toast("发送失败：" + (e.message ?: e.toString()))
        }
    }

    private fun confirmDelete(file: File, title: String) {
        AlertDialog.Builder(this)
            .setTitle("删除缓存")
            .setMessage("确定从手机缓存删除《$title》？手环上的那份不受影响。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                repository.delete(file.name)
                LogBus.log("已删除歌词：${file.name}")
                render()
            }
            .show()
    }

    // ---------- 导入 ----------

    private fun pickLrc() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            toast("无法打开文件选择器")
        }
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
        var ok = 0
        for (uri in uris) {
            if (repository.import(uri) != null) {
                ok++
            }
        }
        toast("已导入 $ok 个歌词到手机缓存")
        switchMode(MODE_LOCAL)
    }

    private fun importPlaylist() {
        if (importing) {
            toast("正在导入，请稍候")
            return
        }
        val input = playlistInput.text.toString()
        val id = MusicApi.playlistId(input)
        if (id == null) {
            toast("无法识别歌单链接或 ID")
            return
        }
        importing = true
        importButton.isEnabled = false
        setProgress("正在读取歌单…")

        worker.execute {
            var added = 0
            var skipped = 0
            var failed = 0
            var total = 0
            try {
                val playlist = MusicApi.playlist(id, MAX_IMPORT)
                if (playlist == null || playlist.tracks.isEmpty()) {
                    main {
                        importing = false
                        importButton.isEnabled = true
                        setProgress("歌单读取失败，请检查链接或网络后重试")
                    }
                    return@execute
                }
                val tracks = playlist.tracks.take(MAX_IMPORT)
                total = tracks.size
                // 词库索引只读一次，后面全是内存比对
                val existing = try {
                    repository.keySet()
                } catch (e: Exception) {
                    emptySet<String>()
                }
                val head = playlist.name.ifEmpty { "歌单 $id" }
                main { setProgress("《$head》共 ${playlist.trackCount} 首，开始导入前 $total 首…") }

                for ((index, track) in tracks.withIndex()) {
                    val label = track.name
                    val key = repository.keyOf(track.name, track.artist)
                    if (existing.contains(key)) {
                        skipped++
                        progress(index, total, label, added, skipped, failed)
                        continue
                    }
                    var lrc = try {
                        MusicApi.lyric(track.id)
                    } catch (e: Exception) {
                        null
                    }
                    if (lrc.isNullOrBlank()) {
                        lrc = try {
                            OnlineLyrics.fetch(track.name, track.artist, track.duration)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (lrc.isNullOrBlank()) {
                        failed++
                    } else if (repository.save(track.name, track.artist, lrc) != null) {
                        added++
                    } else {
                        failed++
                    }
                    progress(index, total, label, added, skipped, failed)
                }
            } catch (e: Exception) {
                LogBus.log("歌单导入异常：${e.message ?: e}")
            }
            main {
                importing = false
                importButton.isEnabled = true
                setProgress("完成：新增 $added · 跳过 $skipped · 失败 $failed（共 $total 首）\n在「手机缓存」里逐首发送，或播放时自动匹配")
                LogBus.log("歌单导入完成：新增 $added，跳过 $skipped，失败 $failed")
                render()
            }
        }
    }

    private fun progress(index: Int, total: Int, name: String, added: Int, skipped: Int, failed: Int) {
        val current = index + 1
        main {
            setProgress("[$current/$total] $name\n新增 $added · 跳过 $skipped · 失败 $failed")
        }
    }

    private fun setProgress(text: String) {
        progressView.visibility = View.VISIBLE
        progressView.text = text
    }

    private fun main(block: () -> Unit) {
        handler.post { block() }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
