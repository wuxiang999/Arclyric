package com.vela.lyricplayer

import org.json.JSONObject

object Protocol {
    private const val VERSION = 1

    private fun base(type: String): JSONObject {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", type)
        obj.put("ts", System.currentTimeMillis())
        obj.put("payload", JSONObject())
        return obj
    }

    // 心跳：手环端收到后立即回 pong + 日志，用于确认手环脚本是否真的在运行
    fun ping(): JSONObject = base("ping")

    fun state(
        title: String,
        artist: String,
        album: String,
        duration: Long,
        position: Long,
        playing: Boolean,
        source: String,
        volume: Int = -1
    ): JSONObject {
        val payload = JSONObject()
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("duration", duration)
            .put("position", position)
            .put("playing", playing)
            .put("source", source)
        if (volume >= 0) {
            payload.put("volume", volume)
        }
        return base("state").put("payload", payload)
    }

    fun lyrics(title: String, artist: String, lrc: String, persist: Boolean): JSONObject {        val payload = JSONObject()
            .put("title", title)
            .put("artist", artist)
            .put("lrc", lrc)
            .put("persist", persist)
        return base("lyrics").put("payload", payload)
    }

    fun chunk(
        id: String,
        index: Int,
        total: Int,
        text: String,
        title: String,
        artist: String,
        persist: Boolean
    ): JSONObject {
        val payload = JSONObject()
            .put("id", id)
            .put("index", index)
            .put("total", total)
            .put("text", text)
            .put("title", title)
            .put("artist", artist)
            .put("persist", persist)
        return base("lyrics.chunk").put("payload", payload)
    }

    fun chunks(
        title: String,
        artist: String,
        lrc: String,
        persist: Boolean,
        size: Int = 100
    ): List<JSONObject> {
        if (lrc.length <= size) {
            return listOf(lyrics(title, artist, lrc, persist))
        }
        val id = "L" + System.currentTimeMillis().toString(36)
        val total = (lrc.length + size - 1) / size
        val result = ArrayList<JSONObject>(total)
        for (index in 0 until total) {
            val start = index * size
            val end = minOf(lrc.length, start + size)
            result.add(chunk(id, index, total, lrc.substring(start, end), title, artist, persist))
        }
        return result
    }

    /** 请求手环汇报它自己那本歌词（手环会分片回传） */
    fun libraryRequest(): JSONObject = base("library.request")

    /** 让手环删掉词库里的某一首 */
    fun libraryDelete(key: String): JSONObject {
        val payload = JSONObject().put("key", key)
        return base("library.delete").put("payload", payload)
    }
}
