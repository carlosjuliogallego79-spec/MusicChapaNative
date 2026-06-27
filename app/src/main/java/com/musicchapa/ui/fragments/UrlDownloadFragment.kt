package com.musicchapa.ui.fragments

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import com.musicchapa.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ytdlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ytdlpReady = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)

        warmupYtdlp()

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            urlInput.text.clear()
            progressBar.visibility = View.VISIBLE
            statusText.text = "Descargando..."
            scope.launch { downloadAudio(url, statusText, progressBar) }
        }
        return view
    }

    private fun warmupYtdlp() {
        ytdlpScope.launch {
            try { YoutubeDL.getInstance().updateYoutubeDL(requireContext()) } catch (_: Exception) {}
            try {
                withTimeout(15_000) { YoutubeDL.getInstance().execute(YoutubeDLRequest("--version")) }
                ytdlpReady = true
            } catch (_: Exception) {}
        }
    }

    private suspend fun downloadAudio(url: String, statusText: android.widget.TextView, progressBar: ProgressBar) {
        val ctx = requireContext()

        var attempts = 0
        while (!ytdlpReady && attempts < 20) { delay(1000); attempts++ }
        if (!ytdlpReady) {
            progressBar.visibility = View.GONE
            statusText.text = "Error: yt-dlp no disponible"
            return
        }

        // Get song title first
        val songTitle = withContext(Dispatchers.IO) {
            try {
                withTimeout(15_000) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--no-check-certificate")
                    req.addOption("--skip-download")
                    req.addOption("--print", "title")
                    YoutubeDL.getInstance().execute(req).out.trim()
                }
            } catch (_: Exception) { null }
        } ?: "audio_${System.currentTimeMillis()}"

        val safeTitle = songTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").take(100)

        val result = withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.cacheDir, "ytdlp")
                dir.mkdirs()

                withTimeout(180_000) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--no-check-certificate")
                    req.addOption("--socket-timeout", "15")
                    req.addOption("--extract-audio")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "0")
                    req.addOption("-o", "${dir.absolutePath}/$safeTitle.%(ext)s")
                    req.addOption("-f", "bestaudio/best")
                    YoutubeDL.getInstance().execute(req)
                }

                val mp3 = File(dir, "$safeTitle.mp3")
                if (!mp3.exists() || mp3.length() == 0L) return@withContext "no se generó MP3"

                saveToDownloads(mp3, songTitle)
                null
            } catch (e: Exception) { e.message ?: "error" }
        }
        progressBar.visibility = View.GONE
        statusText.text = if (result == null) "Completa ✓" else "Error: $result"
    }

    private fun saveToDownloads(file: File, title: String) {
        val ctx = requireContext()
        val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(100)
        val fileName = "$safe.mp3"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.SIZE, file.length())
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val output = ctx.contentResolver.openOutputStream(uri)
                if (output != null) {
                    file.inputStream().use { input -> output.use { out -> input.copyTo(out) } }
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

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
