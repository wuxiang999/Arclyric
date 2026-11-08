package com.vela.lyricplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

class PlayerService : Service() {
    companion object {
        // 单包字符数：中文 UTF-8 下 160 字约 500 字节，留足协议余量
        // 单包 100 字：中文 UTF-8 约 300 字节，加协议头仍在 450 字节内。
        // 手环的通信缓冲很小，包一大就是整机重启，宁慢勿崩。
        private const val CHUNK_CHARS = 100
        private const val PACKET_TIMEOUT_MS = 1500L
        private const val MAX_LYRICS_CHARS = 3000

        const val ACTION_START = "com.vela.lyricplayer.action.START"
        const val ACTION_STOP = "com.vela.lyricplayer.action.STOP"
        const val ACTION_RESEND = "com.vela.lyricplayer.action.RESEND"
        const val ACTION_PUSH = "com.vela.lyricplayer.action.PUSH"
        const val ACTION_LIBRARY = "com.vela.lyricplayer.action.LIBRARY"
        const val ACTION_LIBRARY_DELETE = "com.vela.lyricplayer.action.LIBRARY_DELETE"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_KEY = "key"

        private const val CHANNEL_ID = "arclyric_sync"
        private const val NOTIFICATION_ID = 4101

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            // 已在运行就别再 start 一遍：每次 start 都会重投 onStartCommand，
            // 歌词库页轮询时会把主循环打断成每秒好几次。
            if (running) {
                return
            }
            val intent = Intent(context, PlayerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }

        fun resend(context: Context) {
            context.startService(Intent(context, PlayerService::class.java).setAction(ACTION_RESEND))
        }

        /** 让手环把它自己那本词库报上来（歌词库界面就是它的镜像） */
        fun requestLibrary(context: Context) {
            try {
                val intent = Intent(context, PlayerService::class.java).setAction(ACTION_LIBRARY)
                context.startService(intent)
            } catch (e: Exception) {
                LogBus.log("请求词库失败：" + (e.message ?: e.toString()))
            }
        }

        /** 删掉手环词库里的某一首（歌词库界面的删除） */
        fun deleteFromBand(context: Context, key: String) {
            try {
                val intent = Intent(context, PlayerService::class.java)
                    .setAction(ACTION_LIBRARY_DELETE)
                    .putExtra(EXTRA_KEY, key)
                context.startService(intent)
            } catch (e: Exception) {
                LogBus.log("删除词库失败：" + (e.message ?: e.toString()))
            }
        }

        /** 把词库里的某个 .lrc 直接推到手环 */
        fun pushFile(context: Context, name: String) {
            val intent = Intent(context, PlayerService::class.java)
                .setAction(ACTION_PUSH)
                .putExtra(EXTRA_FILE, name)
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: LyricsRepository
    private lateinit var bridge: WearBridge

    private var controller: MediaController? = null
    private var lastSignature = ""
    private var lastTitle = ""
    private var lastArtist = ""
    private var lastLyricsKey = ""
    private var lyricsFailedKey = ""
    private val fetching = HashSet<String>()
    private val fetchExecutor = Executors.newSingleThreadExecutor()
    private var forceResend = false
    private var ticking = false
    private var lastAccessWarn = 0L
    private var lastSendAt = 0L
    private var lyricGeneration = 0
    private var pendingPackets: List<JSONObject>? = null
    private var pendingIndex = 0
    private var pendingAcks = 0
    private var pendingGeneration = 0
    private var pendingId = ""
    private var lastPongAt = 0L

    // 词库镜像：手环是唯一真源，这里只负责向它要一份快照
    private var libraryRequested = false
    private var libraryRequestTries = 0
    private var lastLibraryRequestAt = 0L
    private var lastLibraryAt = 0L

    // 状态每秒都可能丢一条，靠 10 秒兜底重发保证手环最终一定会看到新歌
    private var lastStateAt = 0L
    private var lyricsDelivered = false
    private var lyricsRetry = 0
    private var lastPushAt = 0L
    private var lastPushTitle = ""
    private var lastPushArtist = ""
    private var lastPushText = ""

    private val tick = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        repository = LyricsRepository(this)
        bridge = WearBridge(
            applicationContext,
            { text -> handler.post { handleBandMessage(text) } },
            { status -> LogBus.log(status) }
        )
        bridge.connect()
        LogBus.log("同步服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须最先转前台：从歌词库直接推歌时会用 startForegroundService，
        // 5 秒内不转前台会被系统杀掉。
        startForegroundCompat()
        when (intent?.action) {
            ACTION_RESEND -> forceResend = true
            ACTION_PUSH -> pushFileToBand(intent.getStringExtra(EXTRA_FILE))
            ACTION_LIBRARY -> {
                libraryRequested = true
                libraryRequestTries = 0
            }
            ACTION_LIBRARY_DELETE -> handleLibraryDelete(intent.getStringExtra(EXTRA_KEY))
        }
        if (libraryRequested) {
            // 连接还没建好时先放着，由 poll 在窗口期内补发一次
            requestBandLibrary()
        }
        if (!ticking) {
            ticking = true
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        fetchExecutor.shutdownNow()
        bridge.close()
        LogBus.log("同步服务已停止")
        super.onDestroy()
    }

    private fun pushFileToBand(name: String?) {
        if (name.isNullOrBlank()) {
            return
        }
        // 从歌词库冷启服务时连接还没建立，等 1.2 秒再发，避免第一包丢失
        handler.postDelayed({ doPushFileToBand(name) }, 1200L)
    }

    private fun doPushFileToBand(name: String) {
        val file = java.io.File(filesDir, "lyrics/" + name)
        val text = repository.read(file)
        if (text.isBlank()) {
            LogBus.log("推送失败：歌词文件为空（$name）")
            return
        }
        val (title, artist) = repository.label(file)
        SyncStatus.update(title, artist, "歌词：已推送")
        LogBus.log("手动推送词库歌词：$title")
        // 手推的词库内容优先于当前播放歌曲的在线匹配结果
        lastPushTitle = title
        lastPushArtist = artist
        pushLyrics(title, artist, text, "词库")
    }

    private fun poll() {
        bridge.ensureConnected()
        keepAlive()
        if (libraryRequested) {
            requestBandLibrary()
        }
        if (!MediaWatcher.hasAccess(this)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAccessWarn > 30000L) {
                lastAccessWarn = now
                LogBus.log("缺少通知使用权，无法读取播放状态")
            }
            return
        }
        val playing = MediaWatcher.snapshot(this) ?: return
        controller = playing.controller
        val signature = buildString {
            append(playing.title)
            append('|')
            append(playing.artist)
            append('|')
            append(playing.playing)
            append('|')
            append(playing.duration)
            append('|')
            append(playing.position / 10000L)
        }
        if (forceResend) {
            forceResend = false
            sendState(playing)
            sendLyrics(playing.title, playing.artist, true, playing.duration)
            lastSignature = signature
            lastStateAt = SystemClock.elapsedRealtime()
            lastTitle = playing.title
            lastArtist = playing.artist
            return
        }
        val now = SystemClock.elapsedRealtime()
        // 只在变化时发 + 每 10 秒兜底重发一次：手环侧即使漏掉一两条也能自愈
        if (signature != lastSignature || now - lastStateAt > 10000L) {
            lastSignature = signature
            lastStateAt = now
            sendState(playing)
        }
        if (playing.title != lastTitle || playing.artist != lastArtist) {
            val from = if (lastTitle.isBlank()) "-" else lastTitle
            LogBus.log("切歌：$from → ${playing.title}")
            lastTitle = playing.title
            lastArtist = playing.artist
            lyricsRetry = 0
            sendLyrics(playing.title, playing.artist, true, playing.duration)
        } else if (!lyricsDelivered && pendingPackets == null && lastPushTitle == playing.title &&
            lastPushText.isNotEmpty() && lyricsRetry < 2 && now - lastPushAt > 12000L
        ) {
            lyricsRetry++
            LogBus.log("歌词未确认送达，第 $lyricsRetry 次重发")
            pushLyrics(lastPushTitle, lastPushArtist, lastPushText, "重发")
        }
    }

    // 手环端收不到任何消息就不会建立会话，定期发 ping 兼作存活探测
    private fun keepAlive() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendAt < 10000L || !bridge.isReady()) {
            return
        }
        transmit(Protocol.ping())
    }

    private fun handleLibraryDelete(key: String?) {
        if (key.isNullOrBlank()) {
            return
        }
        transmit(Protocol.libraryDelete(key))
        LogBus.log("让手环删除词库：$key")
        // 手环删完会回一版词库，稍等一下再要
        handler.postDelayed({
            libraryRequested = true
            libraryRequestTries = 0
            lastLibraryRequestAt = 0L
            requestBandLibrary()
        }, 900L)
    }

    // 手环分片回传词库；没收到就每 5 秒补问一次，最多 5 次，避免空转
    private fun requestBandLibrary() {
        if (!bridge.isReady()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLibraryRequestAt < 5000L) {
            return
        }
        if (libraryRequestTries >= 5) {
            libraryRequested = false
            libraryRequestTries = 0
            LogBus.log("手环没有回词库，等下次自动重试")
            return
        }
        libraryRequestTries++
        lastLibraryRequestAt = now
        transmit(Protocol.libraryRequest())
        if (libraryRequestTries == 1) {
            LogBus.log("请求手环词库")
        }
    }

    private fun transmit(envelope: JSONObject) {
        lastSendAt = SystemClock.elapsedRealtime()
        bridge.send(envelope)
    }

    private fun sendState(playing: NowPlaying) {
        transmit(
            Protocol.state(
                playing.title,
                playing.artist,
                playing.album,
                playing.duration,
                playing.position,
                playing.playing,
                playing.source
            )
        )
    }

    private fun sendLyrics(title: String, artist: String, force: Boolean, durationMs: Long = 0L) {
        if (title.isBlank()) {
            return
        }
        val key = "$title|$artist"
        if (!force && key == lastLyricsKey) {
            return
        }
        lastLyricsKey = key

        val local = repository.find(title, artist)
        if (!local.isNullOrBlank()) {
            SyncStatus.update(title, artist, "歌词：本地词库")
            pushLyrics(title, artist, local, "本地")
            return
        }

        if (key == lyricsFailedKey) {
            return
        }
        if (!fetching.add(key)) {
            return
        }
        SyncStatus.update(title, artist, "歌词：在线匹配中…")
        LogBus.log("本地无《$title》，正在在线匹配歌词")
        fetchExecutor.execute {
            val found = try {
                OnlineLyrics.fetch(title, artist, durationMs)
            } catch (e: Exception) {
                LogBus.log("在线歌词请求失败：${e.message ?: e}")
                null
            }
            handler.post {
                fetching.remove(key)
                if (found.isNullOrBlank()) {
                    lyricsFailedKey = key
                    SyncStatus.update(title, artist, "歌词：未匹配到")
                    LogBus.log("未匹配到《$title》的歌词，仅同步播放状态")
                    return@post
                }
                repository.save(title, artist, found)
                SyncStatus.update(title, artist, "歌词：在线匹配成功")
                if (key == lastLyricsKey) {
                    pushLyrics(title, artist, found, "在线")
                }
            }
        }
    }

    // 手环回执只用于统计与限速：即使一条回执都收不到，也要把歌词发完，
    // 不能因为回执通道异常就整首歌词都丢掉。
    private fun pushLyrics(title: String, artist: String, text: String, source: String) {
        val trimmed = trimLyrics(text, MAX_LYRICS_CHARS)
        if (trimmed.isBlank()) {
            return
        }
        lastPushAt = SystemClock.elapsedRealtime()
        lastPushTitle = title
        lastPushArtist = artist
        lastPushText = trimmed
        lyricsDelivered = false
        val packets = Protocol.chunks(title, artist, trimmed, true, CHUNK_CHARS)
        lyricGeneration++
        pendingGeneration = lyricGeneration
        pendingPackets = packets
        pendingIndex = 0
        pendingAcks = 0
        pendingId = packetId(packets)
        LogBus.log("开始发送$source 歌词：$title · ${trimmed.length} 字 / ${packets.size} 包")
        SyncStatus.update(title, artist, "歌词：发送中 0/${packets.size} 包")
        sendPendingPacket()
    }

    private fun packetId(packets: List<JSONObject>): String {
        val payload = packets.firstOrNull()?.optJSONObject("payload") ?: return ""
        return payload.optString("id", "")
    }

    private fun sendPendingPacket() {
        val packets = pendingPackets ?: return
        val total = packets.size
        if (pendingIndex >= total) {
            finishPending(total)
            return
        }
        val index = pendingIndex
        transmit(packets[index])
        SyncStatus.update(lastTitle, lastArtist, "歌词：发送中 ${index + 1}/$total 包")
        handler.postDelayed({
            if (pendingPackets === packets && pendingIndex == index) {
                // 没等到回执：不重试、不中止，直接推进到下一包
                pendingIndex++
                if (pendingIndex >= total) {
                    finishPending(total)
                } else {
                    sendPendingPacket()
                }
            }
        }, PACKET_TIMEOUT_MS)
    }

    private fun onChunkAck(id: String) {
        val packets = pendingPackets ?: return
        if (id.isNotEmpty() && pendingId.isNotEmpty() && id != pendingId) {
            return
        }
        pendingAcks++
        pendingIndex++
        if (pendingIndex >= packets.size) {
            finishPending(packets.size)
        } else {
            sendPendingPacket()
        }
    }

    private fun finishPending(total: Int) {
        val acks = pendingAcks
        closePending()
        if (acks >= total) {
            lyricsDelivered = true
            SyncStatus.update(lastTitle, lastArtist, "歌词：已送达手环（$total 包）")
            LogBus.log("歌词分片已全部送达（$total 包）")
        } else if (acks > 0) {
            LogBus.log("歌词已发送（$total 包，回执 $acks/$total）")
        } else {
            LogBus.log("歌词已发送（$total 包，手环未回执）")
            SyncStatus.update(lastTitle, lastArtist, "歌词：已发送，无回执")
        }
    }

    private fun closePending() {
        pendingPackets = null
        pendingIndex = 0
        pendingAcks = 0
        pendingId = ""
    }

    private fun trimLyrics(text: String, limit: Int): String {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.length <= limit) {
            return clean
        }
        val cut = clean.lastIndexOf('\n', limit)
        return if (cut > limit / 2) clean.substring(0, cut) else clean.substring(0, limit)
    }

    private fun handleBandMessage(text: String) {
        val json = parseEnvelope(text)
        if (json == null) {
            LogBus.log("收到无法解析的消息")
            return
        }
        when (json.optString("type")) {
            "ack" -> {
                val payload = json.optJSONObject("payload") ?: JSONObject()
                val ok = payload.optBoolean("ok", false)
                when (payload.optString("for")) {
                    "chunk" -> if (ok) onChunkAck(payload.optString("id", ""))
                    "lyrics" -> if (ok) {
                        lyricsDelivered = true
                        LogBus.log("手环已收到歌词（${payload.optInt("chars")} 字）")
                        SyncStatus.update(lastTitle, lastArtist, "歌词：已送达手环")
                    }
                }
            }
            "library.chunk" -> {
                val payload = json.optJSONObject("payload") ?: JSONObject()
                val done = BandLibrary.onChunk(
                    payload.optString("id"),
                    payload.optInt("seq", 0),
                    payload.optInt("total", 1),
                    payload.optString("text"),
                    payload.optInt("count", 0)
                )
                if (done) {
                    lastLibraryAt = SystemClock.elapsedRealtime()
                    libraryRequested = false
                    libraryRequestTries = 0
                    LogBus.log("手环词库已同步：${BandLibrary.items.size} 首")
                }
            }
            "pong" -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPongAt > 30000L) {
                    lastPongAt = now
                    LogBus.log("手环在线（pong）")
                }
            }
            "hello" -> {
                LogBus.log("手环已连上，1.5 秒后推送状态与歌词")
                handler.postDelayed({
                    libraryRequested = true
                    libraryRequestTries = 0
                    lastLibraryRequestAt = 0L
                    requestBandLibrary()
                }, 2500L)
                // 握手刚建立时通信服务还在抖动，立刻灌数据容易把穿戴服务带崩
                handler.postDelayed({
                    forceResend = true
                    poll()
                }, 1500L)
            }
            "debug" -> {
                val payload = json.optJSONObject("payload") ?: JSONObject()
                val level = payload.optString("level", "info")
                val tag = payload.optString("tag", "band")
                val msg = payload.optString("msg", "")
                val marker = when (level) {
                    "boot" -> "[启动]"
                    "error", "crash" -> "[异常]"
                    "warn" -> "[警告]"
                    "alive" -> "[心跳]"
                    else -> "[日志]"
                }
                LogBus.log("$marker $tag · $msg")
            }
            "command" -> {
                val payload = json.optJSONObject("payload") ?: JSONObject()
                val action = payload.optString("action")
                val value = payload.optLong("value", 0L)
                when (action) {
                    "sync" -> {
                        forceResend = true
                        poll()
                    }
                    "lyrics" -> sendLyrics(lastTitle, lastArtist, true)
                    "play", "pause", "toggle", "next", "prev", "seek" -> {
                        if (!MediaWatcher.hasAccess(this)) {
                            LogBus.log("缺少通知使用权，无法控制播放")
                            return
                        }
                        if (controller == null) {
                            controller = MediaWatcher.snapshot(this)?.controller
                        }
                        MediaWatcher.execute(controller, action, value)
                        LogBus.log("手环控制：$action")
                    }
                    else -> LogBus.log("未知指令：$action")
                }
            }
        }
    }

    // 手环发来的可能是：裸 JSON / JSON 字符串 / {data:'<json>'} / 多层嵌套。
    // 官方样例就是 {data:{data:'<json>'}}，所以逐层剥壳直到拿到带 type 的对象。
    private fun parseEnvelope(text: String): JSONObject? {
        var current: Any? = try {
            JSONTokener(text).nextValue()
        } catch (e: Exception) {
            return null
        }
        var guard = 0
        while (guard++ < 6) {
            when (val value = current) {
                is JSONObject -> {
                    if (value.optString("type").isNotEmpty()) {
                        return value
                    }
                    current = when {
                        value.has("d") -> value.opt("d")
                        value.has("data") -> value.opt("data")
                        else -> return null
                    }
                }
                is String -> {
                    current = try {
                        JSONTokener(value).nextValue()
                    } catch (e: Exception) {
                        return null
                    }
                }
                else -> return null
            }
        }
        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放同步",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "把手环当前播放状态与歌词同步到弧光歌词"
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("弧光歌词正在同步")
            .setContentText("在手机上播放，手环上查看歌词")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
