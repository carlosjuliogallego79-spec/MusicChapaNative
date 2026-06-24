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
import org.json.JSONObject
import java.io.File
import java.net.URL

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val results = mutableListOf<Pair<String, String>>() // title, videoId

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
                scope.launch { searchYoutube(query, adapter) }
            }
        }
        return view
    }

    private suspend fun searchYoutube(query: String, adapter: YoutubeResultAdapter) = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}")
            val html = url.readText()
            // Simple extract of video IDs and titles from search results
            val regex = Regex("""videoId":"([^"]+)",""")
            val titleRegex = Regex("""title":{"runs":\[{"text":"([^"]+)"}""")
            val ids = regex.findAll(html).map { it.groupValues[1] }.distinct().take(15).toList()
            val titles = titleRegex.findAll(html).map { it.groupValues[1] }.take(15).toList()
            val pairs = ids.zip(titles.ifEmpty { ids.map { "Unknown" } })
            withContext(Dispatchers.Main) {
                results.clear()
                results.addAll(pairs)
                adapter.notifyDataSetChanged()
            }
        } catch (e: Exception) {
            // Fallback: show error silently
        }
    }

    private suspend fun downloadAudio(videoId: String) = withContext(Dispatchers.IO) {
        try {
            // Use invidious or youtube audio URL pattern
            val audioUrl = "https://www.youtube.com/watch?v=$videoId"
            val dir = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
            dir.mkdirs()
            val file = File(dir, "${videoId}_${System.currentTimeMillis()}.mp3")
            // For actual YouTube audio extraction, we recommend using yt-dlp or similar tool
            // This is a placeholder - full implementation requires additional libraries
            file.createNewFile()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
