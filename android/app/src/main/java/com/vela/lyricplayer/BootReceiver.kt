package com.vela.lyricplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 开机后自动恢复同步服务，保证手环端日志随时能被回传
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (!MediaWatcher.hasAccess(context)) {
            return
        }
        try {
            PlayerService.start(context)
        } catch (e: Exception) {
            LogBus.log("开机自启失败：" + (e.message ?: e.toString()))
        }
    }
}
