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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ytdlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
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

        val dir = File(ctx.cacheDir, "ytdlp")
        dir.mkdirs()

        // Strategy 1: --get-url + OkHttp (direct URL)
        val audioUrl = withContext(Dispatchers.IO) {
            try {
                withTimeout(30_000) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--no-check-certificate")
                    req.addOption("--socket-timeout", "10")
                    req.addOption("--geo-bypass")
                    req.addOption("--get-url")
                    req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    YoutubeDL.getInstance().execute(req).out.trim()
                }
            } catch (_: Exception) { null }
        }

        if (audioUrl != null && audioUrl.startsWith("http")) {
            val result = withContext(Dispatchers.IO) { downloadWithOkHttp(audioUrl, dir) }
            progressBar.visibility = View.GONE
            statusText.text = if (result == null) "Completa ✓" else "Error: $result"
            return
        }

        // Strategy 2: yt-dlp with --extract-audio
        statusText.text = "Descargando con yt-dlp..."
        val result = withContext(Dispatchers.IO) {
            try {
                withTimeout(180_000) {
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    req.addOption("--no-warnings")
                    req.addOption("--no-check-certificate")
                    req.addOption("--socket-timeout", "15")
                    req.addOption("--geo-bypass")
                    req.addOption("--extract-audio")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "0")
                    req.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
                    req.addOption("-f", "bestaudio/best")
                    YoutubeDL.getInstance().execute(req)
                }

                val mp3 = dir.listFiles()?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
                if (mp3 == null || !mp3.exists() || mp3.length() == 0L) return@withContext "no se generó MP3"

                val title = mp3.nameWithoutExtension.replace(Regex("""[\\/:*?"<>|]"""), "_").take(100)
                saveToDownloads(mp3, title)
                null
            } catch (e: Exception) { e.message ?: "error" }
        }
        progressBar.visibility = View.GONE
        statusText.text = if (result == null) "Completa ✓" else "Error: $result"
    }

    private fun downloadWithOkHttp(audioUrl: String, dir: File): String? {
        try {
            val ctx = requireContext()
            val req = Request.Builder().url(audioUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.104 Mobile Safari/537.36")
                .header("Referer", "https://www.youtube.com/")
                .header("Origin", "https://www.youtube.com")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return "HTTP ${resp.code}" }
            val body = resp.body ?: return "sin body"
            if (body.contentLength() == 0L) { resp.close(); return "vacío" }

            val tempFile = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
            body.byteStream().use { input -> tempFile.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) } }
            resp.close()

            if (!tempFile.exists() || tempFile.length() == 0L) { tempFile.delete(); return "descarga vacía" }

            val title = "audio_${System.currentTimeMillis()}"
            saveToDownloads(tempFile, title)
            return null
        } catch (e: Exception) { return e.message }
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
