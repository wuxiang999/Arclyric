package com.vela.lyricplayer

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 在线歌词匹配：本地没有 .lrc 时自动去公共歌词库找，无需用户手动导入。
 *
 * 中文曲优先 QQ 音乐（简体、命中率高），其他曲优先 LRCLIB（免费开放、无需密钥）。
 * 任一来源命中即返回，调用方负责写入本地词库供下次直接复用。
 */
object OnlineLyrics {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Arclyric) Arclyric/1.0.0 (https://github.com/wuxiang999/arclyric)"

    private const val REFERER_QQ = "https://y.qq.com/"

    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]")

    fun fetch(title: String, artist: String, durationMs: Long): String? {
        val rawTitle = cleanTitle(title)
        if (rawTitle.isBlank()) {
            return null
        }
        var songArtist = cleanArtist(artist)
        if (songArtist.isBlank()) {
            val dash = rawTitle.lastIndexOf(" - ")
            if (dash > 0) {
                songArtist = rawTitle.substring(dash + 3).trim()
            }
        }
        val songTitle = if (songArtist.isNotBlank() && rawTitle.endsWith(" - $songArtist")) {
            rawTitle.substring(0, rawTitle.length - songArtist.length - 3).trim()
        } else {
            rawTitle
        }
        val seconds = if (durationMs in 1000L..3600000L) (durationMs / 1000L).toInt() else 0
        val cjk = CJK.containsMatchIn(songTitle) || CJK.containsMatchIn(songArtist)

        // 第一优先走自家 api.lanhu199.top（网易云聚合，中文命中率最高），
        // 再退到 QQ 音乐 / LRCLIB，任何一步失败都不会影响整体。
        val chain: List<() -> String?> = if (cjk) {
            listOf(
                { MusicApi.fetchLyrics(songTitle, songArtist, durationMs) },
                { qqMusic(songTitle, songArtist, seconds) },
                { lrclibGet(songTitle, songArtist, seconds) },
                { lrclibSearch(songTitle, songArtist, seconds) }
            )
        } else {
            listOf(
                { MusicApi.fetchLyrics(songTitle, songArtist, durationMs) },
                { lrclibGet(songTitle, songArtist, seconds) },
                { lrclibSearch(songTitle, songArtist, seconds) },
                { qqMusic(songTitle, songArtist, seconds) }
            )
        }
        for (step in chain) {
            val text = try {
                step()
            } catch (e: Exception) {
                null
            }
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        return null
    }

    // ---------- LRCLIB ----------

    private fun lrclibGet(title: String, artist: String, seconds: Int): String? {
        if (artist.isBlank()) {
            return null
        }
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=")
            append(enc(title))
            append("&artist_name=")
            append(enc(artist))
            if (seconds > 0) {
                append("&duration=")
                append(seconds)
            }
        }
        val body = httpGet(url, mapOf("User-Agent" to UA, "Accept" to "application/json"))
            ?: return null
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        if (obj.optBoolean("instrumental", false)) {
            return null
        }
        val lrc = obj.optString("syncedLyrics", "")
        return if (looksLikeLrc(lrc)) lrc else null
    }

    private fun lrclibSearch(title: String, artist: String, seconds: Int): String? {
        val url = buildString {
            append("https://lrclib.net/api/search?track_name=")
            append(enc(title))
            if (artist.isNotBlank()) {
                append("&artist_name=")
                append(enc(artist))
            }
        }
        val body = httpGet(url, mapOf("User-Agent" to UA, "Accept" to "application/json"))
            ?: return null
        val array = try {
            JSONArray(body)
        } catch (e: Exception) {
            return null
        }
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optBoolean("instrumental", false)) {
                continue
            }
            val score = scoreBy(
                title,
                artist,
                seconds,
                item.optString("trackName"),
                item.optString("artistName"),
                item.optDouble("duration", 0.0)
            )
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }
        if (best == null || bestScore < 70) {
            return null
        }
        val lrc = best.optString("syncedLyrics", "")
        return if (looksLikeLrc(lrc)) lrc else null
    }

    // ---------- QQ 音乐 ----------

    private fun qqMusic(title: String, artist: String, seconds: Int): String? {
        val keyword = if (artist.isBlank()) title else "$title $artist"
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + enc(keyword) +
            "&format=json&n=10&p=1&cr=1&t=0"
        val body = httpGet(
            searchUrl,
            mapOf("User-Agent" to UA, "Referer" to REFERER_QQ, "Accept" to "application/json")
        ) ?: return null

        val songs = try {
            JSONObject(body).optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list")
                ?: return null
        } catch (e: Exception) {
            return null
        }

        var bestMid = ""
        var bestScore = 0
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val singers = song.optJSONArray("singer")
            val artistText = if (singers != null) {
                val parts = ArrayList<String>()
                for (j in 0 until singers.length()) {
                    singers.optJSONObject(j)?.optString("name")?.let { parts.add(it) }
                }
                parts.joinToString(",")
            } else {
                ""
            }
            val score = scoreBy(
                title,
                artist,
                seconds,
                song.optString("songname"),
                artistText,
                song.optDouble("interval", 0.0)
            )
            if (score > bestScore) {
                bestScore = score
                bestMid = song.optString("songmid", "")
            }
        }
        if (bestMid.isBlank() || bestScore < 70) {
            return null
        }

        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" +
            enc(bestMid) + "&format=json&nobase64=1"
        val lyricBody = httpGet(
            lyricUrl,
            mapOf(
                "User-Agent" to UA,
                "Referer" to "https://y.qq.com/portal/player.html",
                "Accept" to "application/json"
            )
        ) ?: return null
        val lrc = try {
            JSONObject(lyricBody).optString("lyric", "")
        } catch (e: Exception) {
            ""
        }
        val decoded = decodeEntities(lrc)
        return if (looksLikeLrc(decoded)) decoded else null
    }

    // ---------- 工具 ----------

    private fun scoreBy(
        title: String,
        artist: String,
        seconds: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateSeconds: Double
    ): Int {
        val a = norm(title)
        val b = norm(candidateTitle)
        if (a.isEmpty() || b.isEmpty()) {
            return 0
        }
        var score = when {
            a == b -> 100
            b.contains(a) || a.contains(b) -> 55
            else -> return 0
        }
        val na = norm(artist)
        val nb = norm(candidateArtist)
        if (na.isNotEmpty() && nb.isNotEmpty()) {
            when {
                na == nb -> score += 40
                nb.contains(na) || na.contains(nb) -> score += 20
                else -> score -= 35
            }
        }
        if (seconds > 0 && candidateSeconds > 0) {
            val delta = Math.abs(seconds - candidateSeconds)
            score += when {
                delta <= 2 -> 45
                delta <= 5 -> 30
                delta <= 10 -> 12
                delta <= 20 -> 0
                else -> -60
            }
        }
        return score
    }

    internal fun looksLikeLrc(text: String): Boolean {
        if (text.isBlank() || text.length < 12) {
            return false
        }
        if (text.contains("纯音乐") || text.contains("不支持自动滚动")) {
            return false
        }
        var hits = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.length > 5 && t.startsWith("[") && t[3] == ':' && t[1].isDigit() && t[2].isDigit()) {
                hits++
                if (hits >= 3) {
                    return true
                }
            }
        }
        return false
    }

    private fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) {
            return text
        }
        return text
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    private fun cleanTitle(raw: String): String {
        var value = raw.replace('\u0000', ' ').trim()
        value = value.replace(Regex("\\([^)]*\\)"), " ")
        value = value.replace(Regex("（[^）]*）"), " ")
        value = value.replace(Regex("\\[[^\\]]*\\]"), " ")
        value = value.replace(Regex("【[^】]*】"), " ")
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtist(raw: String): String {
        var value = raw.replace('\u0000', ' ').trim()
        for (sep in listOf(" feat.", " feat ", " ft.", " / ", "、", ",", "&", " with ")) {
            val index = value.indexOf(sep, ignoreCase = true)
            if (index > 0) {
                value = value.substring(0, index)
            }
        }
        return value.replace(Regex("\\s+"), " ").trim()
    }

    internal fun norm(value: String): String {
        return value.lowercase()
            .replace(Regex("[\\s\\-_—·・.,，。'\"“”‘’()（）\\[\\]【】!?！？:：/\\\\]"), "")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    internal fun httpGet(url: String, headers: Map<String, String>): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 9000
                instanceFollowRedirects = true
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            if (connection.responseCode !in 200..299) {
                return null
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val builder = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    builder.append(line)
                    line = reader.readLine()
                }
                builder.toString()
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
