package com.vela.lyricplayer

/**
 * 同步状态快照，供 Activity 轮询展示（避免跨进程/跨线程的额外回调）。
 */
object SyncStatus {
    @Volatile
    var nowTitle: String = ""
        private set

    @Volatile
    var nowArtist: String = ""
        private set

    @Volatile
    var lyricsState: String = ""
        private set

    fun update(title: String, artist: String, state: String) {
        nowTitle = title
        nowArtist = artist
        lyricsState = state
    }

    fun clearLyrics(title: String, artist: String) {
        nowTitle = title
        nowArtist = artist
        lyricsState = ""
    }
}
