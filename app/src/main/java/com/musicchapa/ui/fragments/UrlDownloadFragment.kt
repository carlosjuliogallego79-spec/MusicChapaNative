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
import android.widget.TextView
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
    private var pollingJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<TextView>(R.id.status_text)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)

        warmupYtdlp()

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            urlInput.text.clear()
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
            statusText.text = "Iniciando..."
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

    private suspend fun downloadAudio(url: String, statusText: TextView, progressBar: ProgressBar) {
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
        dir.listFiles()?.forEach { it.delete() }

        val formatOptions = listOf(
            "bestaudio[ext=m4a]/bestaudio",
            "bestaudio/best",
            "140/251/250/249"
        )

        for (fmt in formatOptions) {
            statusText.text = "Iniciando..."
            progressBar.isIndeterminate = true
            pollingJob = null

            val downloadJob = CompletableDeferred<String?>()

            val job = scope.launch(Dispatchers.IO) {
                try {
                    withTimeout(120_000) {
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
                        req.addOption("-f", fmt)
                        YoutubeDL.getInstance().execute(req)
                    }

                    val mp3 = dir.listFiles()?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
                            ?: dir.listFiles()?.filter { it.extension == "m4a" }?.maxByOrNull { it.lastModified() }
                    if (mp3 == null || !mp3.exists() || mp3.length() == 0L) {
                        downloadJob.complete("no se generó")
                        return@launch
                    }

                    val title = mp3.nameWithoutExtension.replace(Regex("""[\\/:*?"<>|]"""), "_").take(100)
                    withContext(Dispatchers.Main) {
                        progressBar.progress = 100
                        statusText.text = "Guardando..."
                    }
                    saveToDownloads(mp3, title)
                    downloadJob.complete(null)
                } catch (e: Exception) {
                    downloadJob.complete(e.message ?: "error")
                }
            }

            // Poll for .part files to show progress
            pollingJob = scope.launch(Dispatchers.Default) {
                while (downloadJob.isActive && !downloadJob.isCompleted) {
                    delay(500)
                    val partFiles = dir.listFiles()?.filter { it.name.endsWith(".part") }
                    val currentFile = partFiles?.maxByOrNull { it.length() }
                    if (currentFile != null && currentFile.length() > 0) {
                        val sz = currentFile.length()
                        withContext(Dispatchers.Main) {
                            progressBar.isIndeterminate = false
                            progressBar.max = 10000
                            // Show as percentage of estimated max (100MB)
                            val est = minOf(sz * 10000L / 5_000_000L, 9999L)
                            progressBar.progress = est.toInt()
                            statusText.text = formatSize(sz)
                        }
                    }
                }
            }

            val result = downloadJob.await()
            pollingJob?.cancel()

            if (result == null) {
                progressBar.progress = 100
                statusText.text = "Completa ✓"
                delay(800)
                progressBar.visibility = View.GONE
                return
            }
            statusText.text = "Formato $fmt: $result"
        }
        progressBar.visibility = View.GONE
        statusText.text = "Error: no se pudo descargar"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
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

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
