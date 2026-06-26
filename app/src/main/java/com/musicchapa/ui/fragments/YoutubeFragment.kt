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
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class YoutubeFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
                Toast.makeText(context, "Buscando...", Toast.LENGTH_SHORT).show()

                val searchUrl = "ytsearch10:$query"
                val req = com.yausername.youtubedl_android.YoutubeDLRequest(searchUrl)
                req.addOption("--no-playlist")
                req.addOption("--no-warnings")
                req.addOption("--no-check-certificate")
                req.addOption("--user-agent", "Mozilla/5.0")
                req.addOption("--skip-download")
                req.addOption("--print-json")
                req.addOption("--flat-playlist")

                val resp = withContext(Dispatchers.IO) {
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(req)
                }

                val lines = resp.out.trim().split("\n")
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
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAudio(videoId: String) {
        scope.launch {
            val ctx = requireContext()
            Toast.makeText(ctx, "Descargando...", Toast.LENGTH_SHORT).show()

            val result = withContext(Dispatchers.IO) { downloadWithInnerTube(videoId, ctx) }
            if (result == null) {
                Toast.makeText(ctx, "Completa ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Error: $result", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadWithInnerTube(videoId: String, ctx: android.content.Context): String? {
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
            val response = client.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return "Error: sin respuesta"
            val json = JSONObject(jsonStr)

            val streamingData = json.optJSONObject("streamingData") ?: return "Error: sin streaming data"
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return "Error: sin formatos"

            var audioUrl: String? = null
            var bestBitrate = -1
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio")) continue
                val bitrate = f.optInt("bitrate", 0)
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    audioUrl = f.optString("url")
                    if (audioUrl.isNullOrEmpty()) {
                        val cipher = f.optString("cipher", f.optString("signatureCipher", ""))
                        if (cipher.isNotEmpty()) {
                            val params = cipher.split("&").associate {
                                val parts = it.split("=", limit = 2)
                                parts[0] to java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
                            }
                            val sp = params["sp"] ?: "signature"
                            val sig = params[sp] ?: params["s"] ?: continue
                            audioUrl = params["url"] ?: continue
                            audioUrl = "$audioUrl&$sp=$sig"
                        }
                    }
                }
            }

            if (audioUrl.isNullOrEmpty()) return "Error: URL no encontrada"

            val tempFile = File(ctx.cacheDir, "audio_${videoId.take(8)}.m4a")

            val audioRequest = Request.Builder().url(audioUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .header("Referer", "https://www.youtube.com/")
                .header("Origin", "https://www.youtube.com")
                .build()
            val audioResponse = client.newCall(audioRequest).execute()
            if (!audioResponse.isSuccessful) {
                audioResponse.close()
                return "Error HTTP: ${audioResponse.code}"
            }
            val body = audioResponse.body ?: return "Error: sin body"
            if (body.contentLength() == 0L) { audioResponse.close(); return "Error: vacío" }

            body.byteStream().use { input ->
                tempFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = 65536)
                }
            }
            audioResponse.close()
            if (!tempFile.exists() || tempFile.length() == 0L) { tempFile.delete(); return "Error: descarga vacía" }

            val fileName = "${videoId.take(8)}_${System.currentTimeMillis()}.m4a"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.SIZE, tempFile.length())
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val output = ctx.contentResolver.openOutputStream(uri)
                    if (output != null) {
                        tempFile.inputStream().use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        ctx.contentResolver.update(uri, values, null, null)
                        tempFile.delete()
                        return null
                    }
                }
                return "Error: no se pudo guardar"
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                dir.mkdirs()
                tempFile.copyTo(File(dir, fileName), overwrite = true)
                tempFile.delete()
                return null
            }
        } catch (e: Exception) {
            return e.message ?: "Error desconocido"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
