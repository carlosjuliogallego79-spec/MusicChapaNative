package com.musicchapa

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MusicChapaApp : Application() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            android.util.Log.e("MusicChapa", "Init failed", e)
        }
        scope.launch {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this@MusicChapaApp)
            } catch (_: Exception) {}
        }
    }
}
