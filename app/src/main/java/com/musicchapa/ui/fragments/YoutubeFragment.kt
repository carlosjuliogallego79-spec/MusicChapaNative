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
import org.json.JSONObject
import java.io.File

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val results = mutableListOf<YoutubeResult>()

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

        view.findViewById<android.widget.ImageButton>(R.id.search_btn).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isNotEmpty()) {
                searchYoutube(query, adapter)
            }
        }
        return view
    }

    private fun searchYoutube(query: String, adapter: YoutubeResultAdapter) {
        scope.launch {
            try {
                results.clear()
                adapter.notifyDataSetChanged()
                Toast.makeText(context, "Buscando...", Toast.LENGTH_SHORT).show()

                val searchUrl = "ytsearch10:$query"
                val request = YoutubeDLRequest(searchUrl)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--no-check-certificate")
                request.addOption("--user-agent", "Mozilla/5.0")
                request.addOption("--skip-download")
                request.addOption("--print-json")
                request.addOption("--flat-playlist")

                val response = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request)
                }

                val lines = response.out.trim().split("\n")
                for (line in lines) {
                    try {
                        val json = JSONObject(line.trim())
                        val id = json.optString("id", "")
                        val title = json.optString("title", "Unknown")
                        val author = json.optString("channel", json.optString("uploader", ""))
                        val duration = json.optLong("duration", 0)
                        if (id.isNotEmpty()) {
                            results.add(YoutubeResult(title, id, author, duration))
                        }
                    } catch (_: Exception) { continue }
                }
                adapter.notifyDataSetChanged()
                if (results.isEmpty()) {
                    Toast.makeText(context, "Sin resultados", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error búsqueda: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAudio(videoId: String) {
        scope.launch {
            val ctx = requireContext()
            val url = "https://www.youtube.com/watch?v=$videoId"
            val dir = File(ctx.cacheDir, "ytdlp")
            dir.mkdirs()

            try {
                Toast.makeText(ctx, "Descargando MP3...", Toast.LENGTH_SHORT).show()

                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--no-check-certificate")
                request.addOption("--user-agent", "Mozilla/5.0")
                request.addOption("--extract-audio")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
                request.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
                request.addOption("-f", "bestaudio/best")

                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request)
                }

                val files = dir.listFiles()
                val mp3 = files?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
                if (mp3 == null || !mp3.exists() || mp3.length() == 0L) {
                    Toast.makeText(ctx, "Error: no se generó el MP3", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val safeName = mp3.nameWithoutExtension.replace(Regex("""[\\/:*?"<>|]"""), "_").take(50)
                val fileName = "${safeName}_${videoId.take(8)}.mp3"

                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                        put(MediaStore.Downloads.SIZE, mp3.length())
                    }
                    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        val output = ctx.contentResolver.openOutputStream(uri)
                        if (output != null) {
                            mp3.inputStream().use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            ctx.contentResolver.update(uri, values, null, null)
                            mp3.delete()
                            Toast.makeText(ctx, "Completa: $fileName", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    }
                    mp3.delete()
                    Toast.makeText(ctx, "Error al guardar", Toast.LENGTH_SHORT).show()
                } else {
                    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                    publicDir.mkdirs()
                    mp3.copyTo(File(publicDir, fileName), overwrite = true)
                    mp3.delete()
                    Toast.makeText(ctx, "Completa: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
