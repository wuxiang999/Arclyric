package com.vela.lyricplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.charset.Charset

class LyricsRepository(private val context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    fun list(): List<File> {
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".lrc", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun import(uri: Uri): String? {
        return try {
            val raw = queryName(uri) ?: "imported.lrc"
            val safe = sanitize(raw).let {
                if (it.endsWith(".lrc", ignoreCase = true)) it else "$it.lrc"
            }
            val target = File(dir, safe)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            LogBus.log("已导入歌词：$safe")
            safe
        } catch (e: Exception) {
            LogBus.log("导入失败：${e.message ?: e}")
            null
        }
    }

    fun save(title: String, artist: String, lrc: String): String? {
        if (lrc.isBlank() || title.isBlank()) {
            return null
        }
        return try {
            val base = if (artist.isBlank()) title else "$title - $artist"
            val name = sanitize(base).let {
                if (it.endsWith(".lrc", ignoreCase = true)) it else "$it.lrc"
            }
            val text = lrc.replace("\r\n", "\n").trimEnd()
            val head = StringBuilder()
            if (!hasTag(text, "ti")) {
                head.append("[ti:").append(title).append("]\n")
            }
            if (artist.isNotBlank() && !hasTag(text, "ar")) {
                head.append("[ar:").append(artist).append("]\n")
            }
            File(dir, name).writeText(head.toString() + text + "\n", Charsets.UTF_8)
            name
        } catch (e: Exception) {
            LogBus.log("歌词缓存失败：${e.message ?: e}")
            null
        }
    }

    private fun hasTag(text: String, tag: String): Boolean {
        return text.lineSequence().take(20).any {
            it.trim().startsWith("[$tag:", ignoreCase = true)
        }
    }

    fun find(title: String, artist: String): String? {
        val targetTitle = normalize(title)
        val targetArtist = normalize(artist)
        if (targetTitle.isEmpty()) {
            return null
        }
        var best: File? = null
        var bestScore = 0
        for (file in list()) {
            val text = readSmart(file) ?: continue
            val meta = parseMeta(text)
            val base = file.name.removeSuffix(".lrc")
            val metaTitle = meta.first.ifEmpty { base.substringBefore(" - ") }
            val metaArtist = meta.second.ifEmpty { base.substringAfter(" - ", "") }
            val candidateTitle = normalize(metaTitle)
            val candidateArtist = normalize(metaArtist)
            var score = 0
            if (candidateTitle == targetTitle) {
                score += 100
            } else if (candidateTitle.isNotEmpty() &&
                (candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle))
            ) {
                score += 40
            }
            if (targetArtist.isNotEmpty()) {
                if (candidateArtist == targetArtist) {
                    score += 30
                } else if (candidateArtist.isNotEmpty() && candidateArtist.contains(targetArtist)) {
                    score += 10
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = file
            }
        }
        if (best == null || bestScore < 40) {
            return null
        }
        return readSmart(best)
    }

    /** 读取整首歌词文本（带 GBK 兼容） */
    fun read(file: File): String {
        return readSmart(file) ?: ""
    }

    /** 从文件名 + [ti:]/[ar:] 标签推断标题和歌手 */
    fun label(file: File): Pair<String, String> {
        val base = file.name.removeSuffix(".lrc")
        val text = readSmart(file) ?: ""
        val meta = parseMeta(text)
        val title = meta.first.ifEmpty { base.substringBefore(" - ") }
        val artist = meta.second.ifEmpty { base.substringAfter(" - ", "") }
        return title to artist
    }

    fun lineCount(file: File): Int {
        val text = readSmart(file) ?: return 0
        return text.lineSequence().count { it.trim().startsWith("[") && it.contains("]") && it.contains(":") }
    }

    /**
     * 一次性读出「规范化标题|规范化歌手」集合。
     * 批量导入时用它去重，避免每首歌都把整个词库重读一遍（O(N²) 磁盘读）。
     */
    fun keySet(): Set<String> {
        val set = HashSet<String>()
        for (file in list()) {
            val (title, artist) = label(file)
            if (title.isNotBlank()) {
                set.add(keyOf(title, artist))
            }
        }
        return set
    }

    fun keyOf(title: String, artist: String): String {
        return normalize(title) + "|" + normalize(artist)
    }

    fun delete(name: String): Boolean {
        val target = File(dir, name)
        val ok = try {
            target.delete()
        } catch (e: Exception) {
            false
        }
        if (ok) {
            LogBus.log("已删除歌词：$name")
        }
        return ok
    }

    private fun queryName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (e: Exception) {
            null
        }
    }

    private fun readSmart(file: File): String? {
        return try {
            if (file.length() > 512 * 1024) {
                return null
            }
            val bytes = file.readBytes()
            var text = bytes.toString(Charsets.UTF_8)
            if (text.contains('\uFFFD')) {
                val gbk = try {
                    bytes.toString(Charset.forName("GBK"))
                } catch (e: Exception) {
                    text
                }
                if (!gbk.contains('\uFFFD')) {
                    text = gbk
                }
            }
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMeta(text: String): Pair<String, String> {
        var title = ""
        var artist = ""
        text.lineSequence().take(12).forEach { line ->
            val value = line.trim()
            if (value.startsWith("[ti:", ignoreCase = true) && value.endsWith("]")) {
                title = value.substring(4, value.length - 1).trim()
            }
            if (value.startsWith("[ar:", ignoreCase = true) && value.endsWith("]")) {
                artist = value.substring(4, value.length - 1).trim()
            }
        }
        return title to artist
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(Regex("[\\s\\-_—·・.,，。'\"“”‘’()（）\\[\\]]"), "")
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .take(64)
            .ifEmpty { "imported.lrc" }
    }
}
