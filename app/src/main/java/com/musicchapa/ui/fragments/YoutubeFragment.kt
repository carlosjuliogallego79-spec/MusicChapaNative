package com.musicchapa.ui.fragments

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
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

                val instances = listOf(
                    "https://pipedapi.kavin.rocks/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
                    "https://inv.nadeko.net/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                )
                var jsonStr: String? = null
                for (apiUrl in instances) {
                    try {
                        val request = Request.Builder().url(apiUrl)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        jsonStr = withContext(Dispatchers.IO) {
                            client.newCall(request).execute().body?.string()
                        }
                        if (jsonStr != null) break
                    } catch (_: Exception) { continue }
                }

                if (jsonStr == null) return@launch

                val items = try { JSONArray(jsonStr) } catch (_: Exception) { null }
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        if (item.optString("type", "") == "video") {
                            results.add(YoutubeResult(
                                title = item.optString("title", "Unknown"),
                                videoId = item.optString("videoId", ""),
                                author = item.optString("uploaderName", item.optString("author", "")),
                                duration = item.optLong("duration", 0)
                            ))
                        }
                    }
                } else {
                    val obj = JSONObject(jsonStr)
                    val arr = obj.optJSONArray("items") ?: obj.optJSONArray("videos")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            results.add(YoutubeResult(
                                title = item.optString("title", "Unknown"),
                                videoId = item.optString("videoId", ""),
                                author = item.optString("uploaderName", item.optString("author", "")),
                                duration = item.optLong("duration", item.optLong("lengthSeconds", 0))
                            ))
                        }
                    }
                }
                adapter.notifyDataSetChanged()
            } catch (_: Exception) {}
        }
    }

    private fun downloadAudio(videoId: String) {
        scope.launch {
            val ctx = requireContext()
            val url = "https://www.youtube.com/watch?v=$videoId"
            val dir = File(ctx.getExternalFilesDir(null), "ytdlp")
            dir.mkdirs()

            try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--extract-audio")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
                request.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")

                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request)
                }

                val downloadedFile = dir.listFiles()?.maxByOrNull { it.lastModified() }
                if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0) {
                    moveToDownloads(downloadedFile)
                    Toast.makeText(ctx, "Completa: ${downloadedFile.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Error: archivo no encontrado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun moveToDownloads(file: File) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
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
            file.copyTo(File(publicDir, file.name), overwrite = true)
        }
        file.delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
