package com.vela.lyricplayer

import android.os.Handler
import android.os.Looper

/**
 * 手环端歌词本在手机侧的内存镜像。
 *
 * 手环是这本词库的唯一真源：它在分片回传时（library.chunk）逐片拼回来，
 * 拼完整才替换当前快照，界面永远不会看到半截列表。
 * 手机本地存的那份 `.lrc` 只是「推送缓存」，两者不一定一致。
 */
object BandLibrary {
    data class Item(
        val key: String,
        val title: String,
        val artist: String,
        val lines: Int
    )

    @Volatile
    var items: List<Item> = emptyList()
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    @Volatile
    var connected: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    // 分片拼装缓冲：id -> 片段
    private val buffers = HashMap<String, Array<String?>>()

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        items = emptyList()
        updatedAt = 0L
        buffers.clear()
        notifyChanged()
    }

    /** 收到一片词库数据；返回 true 表示这次刚好拼完整 */
    fun onChunk(id: String, seq: Int, total: Int, text: String, count: Int): Boolean {
        if (total <= 0 || seq < 0 || seq >= total) {
            return false
        }
        var parts = buffers[id]
        if (parts == null || parts.size != total) {
            parts = arrayOfNulls(total)
            buffers[id] = parts
        }
        parts[seq] = text
        if (parts.any { it == null }) {
            return false
        }
        val current = parts
        buffers.remove(id)
        val payload = buildString {
            for (part in current) {
                append(part ?: "")
            }
        }
        items = parse(payload, count)
        updatedAt = System.currentTimeMillis()
        connected = true
        notifyChanged()
        return true
    }

    private fun parse(payload: String, count: Int): List<Item> {
        val list = ArrayList<Item>()
        if (payload.isBlank()) {
            return list
        }
        for (row in payload.split('\n')) {
            if (row.isEmpty()) {
                continue
            }
            val cols = row.split('\t')
            val key = cols.getOrNull(0).orEmpty()
            if (key.isEmpty()) {
                continue
            }
            list.add(
                Item(
                    key = key,
                    title = cols.getOrNull(1).orEmpty().ifEmpty { key.removeSuffix(".lrc") },
                    artist = cols.getOrNull(2).orEmpty(),
                    lines = cols.getOrNull(3)?.toIntOrNull() ?: 0
                )
            )
        }
        // 手环那边按修改时间倒序，这里保持原样；数量对不上说明有文件读失败，也照实显示
        return list
    }

    private fun notifyChanged() {
        main.post {
            for (listener in ArrayList(listeners)) {
                try {
                    listener()
                } catch (e: Exception) {
                    LogBus.log("词库监听异常：" + (e.message ?: e.toString()))
                }
            }
        }
    }
}
