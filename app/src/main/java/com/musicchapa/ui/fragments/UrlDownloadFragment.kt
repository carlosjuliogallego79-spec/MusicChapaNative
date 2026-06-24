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
import com.musicchapa.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            urlInput.text.clear()
            statusText.text = "Procesando..."
            scope.launch { processUrl(url, statusText) }
        }
        return view
    }

    private suspend fun processUrl(url: String, statusText: android.widget.TextView) {
        val videoId = extractYoutubeId(url)
        if (videoId != null) {
            statusText.text = "Obteniendo audio de YouTube..."
            val audioUrl = getYoutubeAudioUrl(videoId)
            if (audioUrl != null) {
                statusText.text = "Descargando audio..."
                val result = downloadFile(audioUrl, videoId)
                statusText.text = result ?: "Descarga completa ✓"
            } else {
                statusText.text = "Error: no se pudo obtener el audio"
            }
        } else {
            statusText.text = "Descargando..."
            val result = downloadFile(url, "song_${System.currentTimeMillis()}")
            statusText.text = result ?: "Descarga completa ✓"
        }
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:youtube\.com/watch\?.*v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})"""),
            Regex("""^([a-zA-Z0-9_-]{11})$""")
        )
        for (p in patterns) {
            p.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    private suspend fun getYoutubeAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val instances = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://inv.nadeko.net/api/v1/videos/$videoId"
        )
        for (apiUrl in instances) {
            try {
                val request = Request.Builder().url(apiUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val jsonStr = client.newCall(request).execute().body?.string() ?: continue
                val json = JSONObject(jsonStr)
                if (apiUrl.contains("pipedapi")) {
                    val streams = json.optJSONArray("audioStreams")
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val s = streams.getJSONObject(i)
                            return@withContext s.optString("url")
                        }
                    }
                    json.optString("audioStreamUrl").takeIf { it.isNotEmpty() }?.let { return@withContext it }
                } else {
                    val formats = json.optJSONArray("adaptiveFormats")
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            if (f.optString("type", "").startsWith("audio")) {
                                return@withContext f.optString("url")
                            }
                        }
                    }
                }
            } catch (_: Exception) { continue }
        }
        null
    }

    private suspend fun downloadFile(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.m4a")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext "Error: no se pudo crear el archivo"

                val output = requireContext().contentResolver.openOutputStream(uri) ?: return@withContext "Error: no se pudo abrir el archivo"
                val connection = URL(url).openConnection()
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.getInputStream().use { input ->
                    output.use { out ->
                        input.copyTo(out, bufferSize = 8192)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                requireContext().contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MusicChapa"
                )
                dir.mkdirs()
                val file = File(dir, "$fileName.m4a")
                val connection = URL(url).openConnection()
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.getInputStream().use { input ->
                    FileOutputStream(file).use { out ->
                        input.copyTo(out, bufferSize = 8192)
                    }
                }
            }
            null
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
