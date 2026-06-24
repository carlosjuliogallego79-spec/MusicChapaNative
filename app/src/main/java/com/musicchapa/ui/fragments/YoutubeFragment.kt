package com.musicchapa.ui.fragments

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.YoutubeResultAdapter
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
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
            val audioUrl = getYoutubeAudioUrl(videoId)
            if (audioUrl != null) {
                saveAudio(audioUrl, videoId)
            }
        }
    }

    private suspend fun getYoutubeAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 30)
                    })
                })
            }
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val jsonStr = client.newCall(request).execute().body?.string() ?: return@withContext null
            val json = JSONObject(jsonStr)

            val streamingData = json.optJSONObject("streamingData") ?: return@withContext null
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return@withContext null

            var bestUrl: String? = null
            var bestBitrate = -1
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                if (mime.startsWith("audio")) {
                    val bitrate = f.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = f.optString("url")
                        if (bestUrl == null) {
                            val cipher = f.optString("cipher", f.optString("signatureCipher", ""))
                            if (cipher.isNotEmpty()) {
                                val params = cipher.split("&").associate {
                                    val parts = it.split("=", limit = 2)
                                    parts[0] to (parts.getOrNull(1) ?: "")
                                }
                                val sp = params["sp"] ?: "signature"
                                val sig = params[sp] ?: params["s"] ?: continue
                                bestUrl = params["url"] ?: continue
                                bestUrl = "$bestUrl&$sp=$sig"
                            }
                        }
                    }
                }
            }
            return@withContext bestUrl
        } catch (_: Exception) { return@withContext null }
    }

    private suspend fun saveAudio(audioUrl: String, videoId: String) = withContext(Dispatchers.IO) {
        try {
            val urlObj = URL(audioUrl)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            conn.instanceFollowRedirects = true
            conn.connect()

            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return@withContext }
            val length = conn.contentLengthLong
            if (length == 0L) { conn.disconnect(); return@withContext }

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "${videoId}_${System.currentTimeMillis()}.m4a")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    if (length > 0) put(MediaStore.Downloads.SIZE, length)
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val output = requireContext().contentResolver.openOutputStream(uri)
                    if (output != null) {
                        conn.inputStream.use { input ->
                            output.use { out ->
                                input.copyTo(out, bufferSize = 65536)
                            }
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    requireContext().contentResolver.update(uri, values, null, null)
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MusicChapa"
                )
                dir.mkdirs()
                val file = File(dir, "${videoId}_${System.currentTimeMillis()}.m4a")
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
