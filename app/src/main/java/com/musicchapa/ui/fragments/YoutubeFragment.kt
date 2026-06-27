package com.musicchapa.ui.fragments

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ytdlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val results = mutableListOf<YoutubeResult>()
    private var ytdlpReady = false

    data class YoutubeResult(val title: String, val videoId: String, val author: String, val duration: Long)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_youtube, container, false)
        val searchInput = view.findViewById<android.widget.EditText>(R.id.search_input)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.results_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = YoutubeResultAdapter(results) { videoId ->
            scope.launch { downloadAudio(videoId) }
        }
        recycler.adapter = adapter

        warmupYtdlp()

        view.findViewById<android.widget.ImageButton>(R.id.search_btn).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                searchYoutube(query, adapter)
            }
        }
        return view
    }

    private fun warmupYtdlp() {
        ytdlpScope.launch {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(requireContext())
            } catch (_: Exception) {}
            try {
                YoutubeDL.getInstance().execute(YoutubeDLRequest("--version"))
                ytdlpReady = true
            } catch (_: Exception) {}
        }
    }

    private fun searchYoutube(query: String, adapter: YoutubeResultAdapter) {
        scope.launch {
            try {
                results.clear()
                adapter.notifyDataSetChanged()
                Toast.makeText(context, "Buscando...", Toast.LENGTH_SHORT).show()

                val req = YoutubeDLRequest("ytsearch10:$query")
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--no-check-certificate")
                req.addOption("--user-agent", "Mozilla/5.0")
                req.addOption("--skip-download")
                req.addOption("--print-json")
                req.addOption("--flat-playlist")

                val resp = withContext(Dispatchers.IO) {
                    withTimeout(30_000) { YoutubeDL.getInstance().execute(req) }
                }

                for (line in resp.out.trim().split("\n")) {
                    try {
                        val json = JSONObject(line.trim())
                        val id = json.optString("id", "")
                        val title = json.optString("title", "Unknown")
                        val author = json.optString("channel", json.optString("uploader", ""))
                        val duration = json.optLong("duration", 0)
                        if (id.isNotEmpty()) results.add(YoutubeResult(title, id, author, duration))
                    } catch (_: Exception) { continue }
                }
                adapter.notifyDataSetChanged()
                if (results.isEmpty()) Toast.makeText(context, "Sin resultados", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAudio(videoId: String) {
        scope.launch {
            val ctx = requireContext()

            // Wait for yt-dlp to be ready
            var attempts = 0
            while (!ytdlpReady && attempts < 20) {
                delay(1000)
                attempts++
            }
            if (!ytdlpReady) {
                Toast.makeText(ctx, "Error: yt-dlp no inicializado", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val url = "https://www.youtube.com/watch?v=$videoId"
            Toast.makeText(ctx, "Obteniendo enlace...", Toast.LENGTH_SHORT).show()

            // Try --get-url first
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
                Toast.makeText(ctx, "Descargando...", Toast.LENGTH_SHORT).show()
                val result = withContext(Dispatchers.IO) { downloadDirect(audioUrl, "m4a") }
                if (result == null) Toast.makeText(ctx, "Completa ✓", Toast.LENGTH_SHORT).show()
                else Toast.makeText(ctx, "Error: $result", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Fallback: yt-dlp full download
            Toast.makeText(ctx, "Descargando con yt-dlp...", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) { downloadWithYtdlp(url) }
            if (result == null) Toast.makeText(ctx, "Completa ✓", Toast.LENGTH_SHORT).show()
            else Toast.makeText(ctx, "Error: $result", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadDirect(audioUrl: String, ext: String): String? {
        try {
            val ctx = requireContext()
            val req = Request.Builder().url(audioUrl)
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

            saveFile(ctx, tempFile, ext)
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

            saveFile(ctx, mp3, "mp3")
            return null
        } catch (e: Exception) { return e.message ?: "error" }
    }

    private fun saveFile(ctx: android.content.Context, file: File, ext: String) {
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
