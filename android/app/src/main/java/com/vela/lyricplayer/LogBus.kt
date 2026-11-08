package com.vela.lyricplayer

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

object LogBus {
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun add(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun log(message: String) {
        val line = LocalTime.now().format(formatter) + "  " + message
        Log.d("Arclyric", line)
        listeners.forEach { it(line) }
    }
}
