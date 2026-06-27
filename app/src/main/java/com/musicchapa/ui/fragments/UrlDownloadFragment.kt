package com.musicchapa.ui.fragments

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.musicchapa.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val ytdlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ytdlpReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)

        // Warm up yt-dlp on first creation
        warmupYtdlp()

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            urlInput.text.clear()
            statusText.text = "Descargando..."
            scope.launch { downloadAudio(url, statusText) }
        }
        return view
    }

    private fun warmupYtdlp() {
        ytdlpScope.launch {
            try {
                // First try updating yt-dlp
                YoutubeDL.getInstance().updateYoutubeDL(requireContext())
            } catch (_: Exception) {}
            try {
                val ver = YoutubeDL.getInstance().execute(YoutubeDLRequest("--version"))
                android.util.Log.i("ytdlp", "Version: ${ver.out.trim()}")
                ytdlpReady = true
            } catch (e: Exception) {
                android.util.Log.e("ytdlp", "Warmup failed", e)
            }
        }
    }

    private suspend fun downloadAudio(url: String, statusText: android.widget.TextView) {
        val ctx = requireContext()

        // Wait for yt-dlp to be ready
        var attempts = 0
        while (!ytdlpReady && attempts < 20) {
            delay(1000)
            attempts++
        }
        if (!ytdlpReady) {
            statusText.text = "Error: yt-dlp no inicializado"
            return
        }

        // Try direct URL download with yt-dlp --get-url
        val audioUrl = withContext(Dispatchers.IO) {
            try {
                withTimeout(30_000) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--no-check-certificate")
                    req.addOption("--socket-timeout", "10")
                    req.addOption("--user-agent", "Mozilla/5.0")
                    req.addOption("--get-url")
                    req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    YoutubeDL.getInstance().execute(req).out.trim()
                }
            } catch (e: Exception) { null }
        }

        if (audioUrl != null) {
            statusText.text = "Descargando audio..."
            val result = withContext(Dispatchers.IO) { downloadWithOkHttp(audioUrl, "m4a") }
            statusText.text = if (result == null) "Completa ✓" else "Error: $result"
            return
        }

        // Fallback: yt-dlp full download
        statusText.text = "Descargando con yt-dlp..."
        val result = withContext(Dispatchers.IO) { downloadWithYtdlp(url) }
        statusText.text = if (result == null) "Completa ✓" else "Error: $result"
    }

    private fun downloadWithOkHttp(url: String, ext: String): String? {
        try {
            val ctx = requireContext()
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .header("Referer", "https://www.youtube.com/")
                .header("Origin", "https://www.youtube.com")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return "HTTP ${resp.code}" }
            val body = resp.body ?: return "sin body"
            if (body.contentLength() == 0L) { resp.close(); return "vacío" }

            val tempFile = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.$ext")
            body.byteStream().use { input -> tempFile.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) } }
            resp.close()

            if (!tempFile.exists() || tempFile.length() == 0L) { tempFile.delete(); return "descarga vacía" }

            saveToDownloads(tempFile, if (ext == "mp3") "mp3" else "m4a")
            return null
        } catch (e: Exception) { return e.message }
    }

    private suspend fun downloadWithYtdlp(url: String): String? {
        try {
            val ctx = requireContext()
            val dir = File(ctx.cacheDir, "ytdlp")
            dir.mkdirs()

            withTimeout(120_000) {
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--no-check-certificate")
                req.addOption("--socket-timeout", "15")
                req.addOption("--user-agent", "Mozilla/5.0")
                req.addOption("--extract-audio")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
                req.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
                req.addOption("-f", "bestaudio/best")
                YoutubeDL.getInstance().execute(req)
            }

            val mp3 = dir.listFiles()?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
            if (mp3 == null || !mp3.exists() || mp3.length() == 0L) return "no se generó MP3"

            saveToDownloads(mp3, "mp3")
            return null
        } catch (e: Exception) { return e.message ?: "error" }
    }

    private fun saveToDownloads(file: File, ext: String) {
        val ctx = requireContext()
        val fileName = "audio_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, if (ext == "mp3") "audio/mpeg" else "audio/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.SIZE, file.length())
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val output = ctx.contentResolver.openOutputStream(uri)
                if (output != null) {
                    file.inputStream().use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                }
            }
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
            publicDir.mkdirs()
            file.copyTo(File(publicDir, fileName), overwrite = true)
        }
        file.delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
