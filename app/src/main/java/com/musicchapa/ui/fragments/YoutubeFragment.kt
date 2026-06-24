package com.musicchapa.ui.fragments

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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
            val instances = listOf(
                "https://inv.nadeko.net/api/v1/videos/$videoId",
                "https://invidious.snopyta.org/api/v1/videos/$videoId"
            )
            var videoTitle = ""
            var bestItag = 140
            for (instance in instances) {
                try {
                    val request = Request.Builder().url(instance)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val jsonStr = withContext(Dispatchers.IO) { client.newCall(request).execute().body?.string() } ?: continue
                    val json = JSONObject(jsonStr)
                    videoTitle = json.optString("title", "Audio")
                    val formats = json.optJSONArray("adaptiveFormats") ?: continue
                    var bestBitrate = 0
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        if (f.optString("type", "").startsWith("audio")) {
                            val bitrate = f.optInt("bitrate", 0)
                            if (bitrate > bestBitrate) {
                                bestBitrate = bitrate
                                bestItag = f.optInt("itag", 140)
                            }
                        }
                    }
                    break
                } catch (_: Exception) { continue }
            }
            saveAudio(videoId, bestItag, videoTitle)
        }
    }

    private suspend fun saveAudio(videoId: String, itag: Int, title: String) = withContext(Dispatchers.IO) {
        val instances = listOf(
            "https://inv.nadeko.net/latest_version?id=$videoId&itag=$itag",
            "https://invidious.snopyta.org/latest_version?id=$videoId&itag=$itag"
        )
        val ext = if (itag == 140) "m4a" else "opus"
        for (dlUrl in instances) {
            try {
                val url = URL(dlUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                val length = conn.contentLengthLong
                if (length == 0L) { conn.disconnect(); continue }

                val safeName = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(50)
                val fileName = "${safeName}_${videoId.take(8)}.$ext"

                var success = false
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, if (ext == "m4a") "audio/mp4" else "audio/opus")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                        if (length > 0) put(MediaStore.Downloads.SIZE, length)
                    }
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        val output = requireContext().contentResolver.openOutputStream(uri)
                        if (output != null) {
                            val total = conn.inputStream.use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            if (total > 0) { values.put(MediaStore.Downloads.SIZE, total); success = true }
                            requireContext().contentResolver.update(uri, values, null, null)
                        }
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    conn.inputStream.use { input -> FileOutputStream(file).use { out -> input.copyTo(out, bufferSize = 65536) } }
                    success = file.exists() && file.length() > 0
                }
                conn.disconnect()
                if (success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Descarga completa: $fileName", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
            } catch (_: Exception) { continue }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
