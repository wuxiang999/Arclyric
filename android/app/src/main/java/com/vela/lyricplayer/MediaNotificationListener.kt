package com.vela.lyricplayer

import android.app.Notification
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListener : NotificationListenerService() {

    // 播放器切歌、暂停、拖动都会更新它自己的媒体通知。
    // 在这里立刻叫醒同步服务抓一次状态，比等主循环每秒轮询更跟手；
    // 没有媒体会话的通知（消息、下载完成等）一律忽略，不会把通道打满。
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!PlayerService.running) {
            return
        }
        val notification = sbn?.notification ?: return
        if (mediaToken(notification) == null) {
            return
        }
        PlayerService.quickPoll?.invoke()
    }

    private fun mediaToken(notification: Notification): MediaSession.Token? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notification.extras?.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                notification.extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }
        } catch (e: Exception) {
            null
        }
    }
}
