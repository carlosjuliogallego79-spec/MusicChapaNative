package com.musicchapa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
                searchInput.text.clear()
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

                val instances = listOf("inv.nadeko.net", "invidious.snopyta.org", "yewtu.be")
                var jsonStr: String? = null
                for (instance in instances) {
                    try {
                        val url = "https://$instance/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                        val request = Request.Builder().url(url).build()
                        jsonStr = withContext(Dispatchers.IO) {
                            client.newCall(request).execute().body?.string()
                        }
                        if (jsonStr != null) break
                    } catch (_: Exception) { continue }
                }

                if (jsonStr == null) return@launch

                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (item.getString("type") == "video") {
                        results.add(YoutubeResult(
                            title = item.optString("title", "Unknown"),
                            videoId = item.optString("videoId", ""),
                            author = item.optString("author", ""),
                            duration = item.optLong("lengthSeconds", 0)
                        ))
                    }
                }
                adapter.notifyDataSetChanged()
            } catch (_: Exception) {}
        }
    }

    private suspend fun downloadAudio(videoId: String) = withContext(Dispatchers.IO) {
        try {
            val instances = listOf("inv.nadeko.net", "invidious.snopyta.org", "yewtu.be")
            var audioUrl: String? = null
            outer@ for (instance in instances) {
                try {
                    val infoUrl = "https://$instance/api/v1/videos/$videoId"
                    val request = Request.Builder().url(infoUrl).build()
                    val jsonStr = client.newCall(request).execute().body?.string() ?: continue
                    val json = org.json.JSONObject(jsonStr)
                    val formats = json.optJSONArray("adaptiveFormats")
                    if (formats != null) {
                        for (j in 0 until formats.length()) {
                            val fmt = formats.getJSONObject(j)
                            if (fmt.optString("encoding") == "aac" || fmt.optString("type")?.startsWith("audio") == true) {
                                audioUrl = fmt.optString("url")
                                if (audioUrl != null) break@outer
                            }
                        }
                    }
                } catch (_: Exception) { continue }
            }

            if (audioUrl == null) return@withContext

            val dir = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
            dir.mkdirs()
            val file = File(dir, "${videoId}_${System.currentTimeMillis()}.mp3")
            val connection = URL(audioUrl).openConnection()
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val input = connection.getInputStream()
            val output = FileOutputStream(file)
            input.copyTo(output, bufferSize = 8192)
            input.close()
            output.close()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
