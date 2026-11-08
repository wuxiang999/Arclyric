package com.vela.lyricplayer

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val position: Long,
    val playing: Boolean,
    val source: String,
    val controller: MediaController?
)

object MediaWatcher {
    private fun component(context: Context): ComponentName {
        return ComponentName(context, MediaNotificationListener::class.java)
    }

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = component(context)
        return enabled.split(":").any { flat ->
            val name = ComponentName.unflattenFromString(flat)
            name != null && name.packageName == target.packageName && name.className == target.className
        }
    }

    fun snapshot(context: Context): NowPlaying? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val sessions = try {
            manager.getActiveSessions(component(context))
        } catch (e: SecurityException) {
            return null
        }
        if (sessions.isEmpty()) {
            return null
        }
        // 选会话的规则（之前只按 lastPositionUpdateTime 取最大，会挑中
        // 「上一首播完停住、却还标着 PLAYING」的旧会话，手环就永远停在上一首和结尾秒数）：
        // 1. 先要 PLAYING；2. 再排除已经播到尾的（多半是没清掉的旧会话）；
        // 3. 最后取最近更新位置的那个。
        val withMeta = sessions.filter { it.metadata != null }
        val playingSessions = withMeta.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val live = playingSessions.filter { !isEnded(it) }
        val candidates = when {
            live.isNotEmpty() -> live
            playingSessions.isNotEmpty() -> playingSessions
            withMeta.isNotEmpty() -> withMeta
            else -> sessions
        }
        val controller = candidates.maxByOrNull {
            it.playbackState?.lastPositionUpdateTime ?: 0L
        } ?: sessions.first()
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: controller.packageName
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = maxOf(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val playback = controller.playbackState
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        var position = playback?.position ?: 0L
        if (playing && playback != null) {
            val speed = if (playback.playbackSpeed > 0f) playback.playbackSpeed else 1f
            val nowElapsed = SystemClock.elapsedRealtime()
            val updatedAt = playback.lastPositionUpdateTime
            // 有些播放器从不设置 lastPositionUpdateTime（值为 0），直接相减会得到
            // 一个天文数字，position 被夹到 duration，表现就是「一暂停就跳到结尾」。
            val delta = if (updatedAt in 1..nowElapsed) nowElapsed - updatedAt else 0L
            position += (delta * speed).toLong()
        }
        position = maxOf(0L, position)
        if (duration > 0) {
            position = minOf(position, duration)
        }
        return NowPlaying(title, artist, album, duration, position, playing, controller.packageName, controller)
    }

    // 播到底的会话：位置贴着总时长。这种会话多半是上一首结束时没被清掉的残留，
    // 只要还有别的正在播的会话，就应该把它排在后面。
    private fun isEnded(controller: MediaController): Boolean {
        val playback = controller.playbackState ?: return false
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            return false
        }
        return playback.position >= duration - 1500L
    }

    fun execute(controller: MediaController?, action: String, value: Long) {
        val transport = controller?.transportControls ?: return
        when (action) {
            "toggle" -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    transport.pause()
                } else {
                    transport.play()
                }
            }
            "play" -> transport.play()
            "pause" -> transport.pause()
            "next" -> transport.skipToNext()
            "prev" -> transport.skipToPrevious()
            "seek" -> transport.seekTo(value)
        }
    }
}
