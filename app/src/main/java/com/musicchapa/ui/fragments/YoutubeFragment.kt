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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ytdlpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val results = mutableListOf<YoutubeResult>()
    private var ytdlpReady = false

    data class YoutubeResult(val title: String, val videoId: String, val author: String, val duration: Long)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_youtube, container, false)
        val searchInput = view.findViewById<android.widget.EditText>(R.id.search_input)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.results_list)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        val adapter = YoutubeResultAdapter(results) { videoId ->
            scope.launch { downloadAudio(videoId, progressBar) }
        }
        recycler.adapter = adapter

        warmupYtdlp()

        view.findViewById<android.widget.ImageButton>(R.id.search_btn).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) searchYoutube(query, adapter)
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

    private fun downloadAudio(videoId: String, progressBar: ProgressBar) {
        scope.launch {
            val ctx = requireContext()

            var attempts = 0
            while (!ytdlpReady && attempts < 20) { delay(1000); attempts++ }
            if (!ytdlpReady) {
                Toast.makeText(ctx, "Error: yt-dlp no disponible", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val url = "https://www.youtube.com/watch?v=$videoId"
            progressBar.visibility = View.VISIBLE
            Toast.makeText(ctx, "Descargando...", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = File(ctx.cacheDir, "ytdlp")
                    dir.mkdirs()

                    val timestamp = System.currentTimeMillis()
                    withTimeout(180_000) {
                        val req = YoutubeDLRequest(url)
                        req.addOption("--no-playlist")
                        req.addOption("--no-warnings")
                        req.addOption("--no-check-certificate")
                        req.addOption("--socket-timeout", "15")
                        req.addOption("--extract-audio")
                        req.addOption("--audio-format", "mp3")
                        req.addOption("--audio-quality", "0")
                        req.addOption("-o", "${dir.absolutePath}/$timestamp.%(ext)s")
                        req.addOption("-f", "bestaudio/best")
                        YoutubeDL.getInstance().execute(req)
                    }

                    val mp3 = File(dir, "$timestamp.mp3")
                    if (!mp3.exists() || mp3.length() == 0L) return@withContext "no se generó MP3"

                    saveFile(ctx, mp3)
                    null
                } catch (e: Exception) { e.message ?: "error" }
            }

            progressBar.visibility = View.GONE
            if (result == null) Toast.makeText(ctx, "Completa ✓", Toast.LENGTH_SHORT).show()
            else Toast.makeText(ctx, "Error: $result", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveFile(ctx: android.content.Context, file: File) {
        val fileName = "${file.nameWithoutExtension}_${System.currentTimeMillis()}.mp3"
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
