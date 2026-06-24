package com.musicchapa

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg

class MusicChapaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        YoutubeDL.getInstance().init(this)
        FFmpeg.getInstance().init(this)
    }
}
