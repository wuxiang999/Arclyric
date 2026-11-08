package com.vela.lyricplayer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自家接口服务（api.lanhu199.top）的音乐聚合接口客户端。
 *
 * 提供的能力（都走 HTTP GET，返回 {code,msg,data}）：
 *  - /api/music.php?type=search&keywords=…   按「歌名 + 歌手」搜网易云曲目
 *  - /api/music.php?type=lyric&id=…          按曲目 id 取 LRC
 *  - /api/music_playlist.php?id=…&limit=&offset=  歌单详情（含曲目列表）
 *
 * 相比直接抓 music.163.com：接口在本机可控、字段稳定、不需要处理加密参数。
 * 任何一步失败都返回 null，由调用方回退到 QQ 音乐 / LRCLIB。
 */
object MusicApi {
    private const val BASE = "https://api.lanhu199.top"
    private const val MUSIC = "$BASE/api/music.php"
    private const val PLAYLIST = "$BASE/api/music_playlist.php"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Arclyric) Arclyric/1.0.0"

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json"
    )

    data class Track(val id: Long, val name: String, val artist: String, val duration: Long)

    data class Playlist(
        val id: String,
        val name: String,
        val creator: String,
        val trackCount: Int,
        val tracks: List<Track>
    )

    /** 从链接、分享文案或纯数字里取出歌单 id */
    fun playlistId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) {
            return null
        }
        val patterns = listOf(
            Regex("playlist[/?=&]id=(\\d+)", RegexOption.IGNORE_CASE),
            Regex("playlist/(\\d+)", RegexOption.IGNORE_CASE),
            Regex("[?&]id=(\\d+)", RegexOption.IGNORE_CASE),
            Regex("^(\\d{4,})$")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        // 分享文案里可能只剩一串数字
        return Regex("\\d{4,}").find(text)?.value
    }

    fun search(keywords: String, limit: Int = 10): List<Track> {
        val word = keywords.trim()
        if (word.isEmpty()) {
            return emptyList()
        }
        val url = "$MUSIC?type=search&limit=$limit&keywords=" + enc(word)
        return parseSongs(request(url))
    }

    fun lyric(songId: Long): String? {
        if (songId <= 0L) {
            return null
        }
        val body = request("$MUSIC?type=lyric&id=$songId") ?: return null
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val lrc = root.optJSONObject("data")
            ?.optJSONObject("lrc")
            ?.optString("lyric")
            .orEmpty()
        if (!OnlineLyrics.looksLikeLrc(lrc)) {
            return null
        }
        return lrc
    }

    /** 按「歌名 + 歌手 + 时长」自动匹配并取词，供自动同步链路使用 */
    fun fetchLyrics(title: String, artist: String, durationMs: Long): String? {
        val cleanTitle = simplify(title)
        if (cleanTitle.isEmpty()) {
            return null
        }
        val cleanArtist = simplify(artist)
        val seconds = if (durationMs in 1000L..3600000L) (durationMs / 1000L).toInt() else 0
        val keywords = listOf(cleanTitle, cleanArtist)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val candidates = search(keywords, 12)
        var best: Track? = null
        var bestScore = 0
        for (track in candidates) {
            val score = score(
                cleanTitle, cleanArtist, seconds,
                simplify(track.name), simplify(track.artist), track.duration
            )
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        val target = best ?: return null
        if (bestScore < 70) {
            return null
        }
        return lyric(target.id)
    }

    /** 歌单详情，max 控制最多取多少首（接口单页上限 100，自动翻页） */
    fun playlist(id: String, max: Int = 100): Playlist? {
        val text = id.trim()
        if (text.isEmpty()) {
            return null
        }
        var name = ""
        var creator = ""
        var trackCount = 0
        val tracks = ArrayList<Track>()
        var offset = 0
        val pageSize = 100
        while (tracks.size < max) {
            val size = minOf(pageSize, max - tracks.size)
            val body = request("$PLAYLIST?id=$text&limit=$size&offset=$offset") ?: break
            val data = try {
                JSONObject(body).optJSONObject("data")
            } catch (e: Exception) {
                null
            } ?: break
            if (name.isEmpty()) {
                name = data.optString("name")
                creator = data.optString("creator")
                trackCount = data.optInt("track_count", 0)
            }
            val page = parseTracks(data.optJSONArray("tracks"))
            tracks.addAll(page)
            if (page.size < size) {
                break
            }
            offset += page.size
        }
        if (tracks.isEmpty() && name.isEmpty()) {
            return null
        }
        return Playlist(text, name, creator, maxOf(trackCount, tracks.size), tracks)
    }

    // ---------- 内部 ----------

    private fun request(url: String): String? {
        val body = OnlineLyrics.httpGet(url, headers) ?: return null
        return try {
            val root = JSONObject(body)
            if (root.optInt("code", 200) != 200) {
                null
            } else {
                body
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSongs(body: String?): List<Track> {
        if (body == null) {
            return emptyList()
        }
        val data = try {
            JSONObject(body).optJSONObject("data")
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()
        return parseTracks(data.optJSONArray("songs"))
    }

    private fun parseTracks(array: JSONArray?): List<Track> {
        if (array == null) {
            return emptyList()
        }
        val list = ArrayList<Track>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) {
                continue
            }
            list.add(
                Track(
                    item.optLong("id", 0L),
                    name,
                    artistOf(item),
                    item.optLong("duration", item.optLong("dt", 0L))
                )
            )
        }
        return list
    }

    private fun artistOf(item: JSONObject): String {
        val direct = item.optString("artists").trim()
        if (direct.isNotEmpty()) {
            return direct
        }
        val array = item.optJSONArray("ar") ?: item.optJSONArray("artists")
        if (array != null) {
            val names = ArrayList<String>()
            for (i in 0 until array.length()) {
                val artist = array.optJSONObject(i) ?: continue
                val name = artist.optString("name").trim()
                if (name.isNotEmpty()) {
                    names.add(name)
                }
            }
            if (names.isNotEmpty()) {
                return names.joinToString("/")
            }
        }
        return item.optString("artist").trim()
    }

    private fun score(
        title: String,
        artist: String,
        seconds: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateMs: Long
    ): Int {
        val a = OnlineLyrics.norm(title)
        val b = OnlineLyrics.norm(candidateTitle)
        if (a.isEmpty() || b.isEmpty()) {
            return 0
        }
        var value = when {
            a == b -> 100
            b.contains(a) || a.contains(b) -> 55
            else -> return 0
        }
        val na = OnlineLyrics.norm(artist)
        val nb = OnlineLyrics.norm(candidateArtist)
        if (na.isNotEmpty() && nb.isNotEmpty()) {
            value += when {
                na == nb -> 40
                nb.contains(na) || na.contains(nb) -> 20
                else -> -40
            }
        }
        if (seconds > 0 && candidateMs > 0L) {
            val delta = Math.abs(seconds - (candidateMs / 1000L).toInt())
            value += when {
                delta <= 2 -> 45
                delta <= 5 -> 30
                delta <= 10 -> 12
                delta <= 20 -> 0
                else -> -60
            }
        }
        return value
    }

    /** 去掉 (Live)/(Remastered)/【】之类后缀，便于跨平台比对 */
    private fun simplify(raw: String): String {
        var value = raw.replace('\u0000', ' ').trim()
        value = value.replace(Regex("\\([^)]*\\)"), " ")
        value = value.replace(Regex("（[^）]*）"), " ")
        value = value.replace(Regex("\\[[^\\]]*\\]"), " ")
        value = value.replace(Regex("【[^】]*】"), " ")
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
