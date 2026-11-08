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
        // 优先选「最近还在更新位置」的播放中会话：
        // 之前固定取第一个 STATE_PLAYING，多个会话并存时会一直读到旧的那一首。
        val playingSessions = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val candidates = if (playingSessions.isNotEmpty()) {
            playingSessions
        } else {
            sessions.filter { it.metadata != null }
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
