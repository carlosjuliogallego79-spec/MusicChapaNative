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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var currentVideoId: String? = null
    private var currentFormats = mutableListOf<AudioFormat>()

    data class AudioFormat(val itag: Int, val label: String, val mime: String, val bitrate: Int)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)
        val formatSpinner = view.findViewById<android.widget.Spinner>(R.id.format_spinner)
        val downloadBtn = view.findViewById<android.widget.Button>(R.id.download_btn)

        formatSpinner.visibility = View.GONE

        downloadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val videoId = extractYoutubeId(url)
            if (videoId != null) {
                urlInput.text.clear()
                statusText.text = "Obteniendo información del video..."
                scope.launch { fetchVideoInfo(videoId, statusText, formatSpinner) }
            } else {
                urlInput.text.clear()
                statusText.text = "Descargando..."
                scope.launch {
                    val result = downloadDirect(url, "song_${System.currentTimeMillis()}", "mp3")
                    statusText.text = result ?: "Descarga completa ✓"
                }
            }
        }
        return view
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

    private suspend fun fetchVideoInfo(videoId: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        val instances = listOf(
            "https://inv.nadeko.net/api/v1/videos/$videoId",
            "https://invidious.snopyta.org/api/v1/videos/$videoId",
            "https://yewtu.be/api/v1/videos/$videoId"
        )
        var videoTitle = ""
        currentFormats.clear()

        for (instance in instances) {
            try {
                val request = Request.Builder().url(instance)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val jsonStr = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().body?.string()
                } ?: continue
                val json = JSONObject(jsonStr)
                videoTitle = json.optString("title", "Video")

                val formats = json.optJSONArray("adaptiveFormats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val mime = f.optString("type", "")
                        val itag = f.optInt("itag", 0)
                        val bitrate = f.optInt("bitrate", 0)
                        if (mime.startsWith("audio")) {
                            val label = when (itag) {
                                140 -> "M4A 128kbps (AAC)"
                                251 -> "Opus 160kbps"
                                250 -> "Opus 70kbps"
                                249 -> "Opus 50kbps"
                                171 -> "WebM 128kbps (Vorbis)"
                                else -> "$itag ${bitrate / 1000}kbps"
                            }
                            currentFormats.add(AudioFormat(itag, label, mime, bitrate))
                        }
                    }
                }
                if (currentFormats.isNotEmpty()) break
            } catch (_: Exception) { continue }
        }

        if (currentFormats.isEmpty()) {
            statusText.text = "Error: no se encontraron formatos de audio"
            return
        }

        currentFormats.sortByDescending { it.bitrate }
        currentVideoId = videoId

        val labels = currentFormats.map { "${it.label} (${videoTitle.take(40)})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.adapter = adapter
        spinner.visibility = View.VISIBLE
        statusText.text = "Seleccioná un formato y tocalo para descargar"

        spinner.onItemSelectedListener = null
        spinner.setSelection(0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val fmt = currentFormats[pos]
                val ext = if (fmt.itag == 140) "m4a" else "opus"
                statusText.text = "Descargando ${fmt.label}..."
                scope.launch {
                    val result = downloadViaInstance(videoId, fmt.itag, videoTitle, ext)
                    statusText.text = result ?: "Descarga completa ✓"
                    if (result == null) spinner.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private suspend fun downloadViaInstance(videoId: String, itag: Int, title: String, ext: String): String? = withContext(Dispatchers.IO) {
        val instances = listOf(
            "https://inv.nadeko.net/latest_version?id=$videoId&itag=$itag",
            "https://invidious.snopyta.org/latest_version?id=$videoId&itag=$itag",
            "https://yewtu.be/latest_version?id=$videoId&itag=$itag"
        )
        for (dlUrl in instances) {
            try {
                val url = URL(dlUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.instanceFollowRedirects = true
                conn.connect()

                val code = conn.responseCode
                if (code != 200) { conn.disconnect(); continue }
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
                if (success) return@withContext null
            } catch (_: Exception) { continue }
        }
        return@withContext "Error: no se pudo descargar de ningún servidor"
    }

    private suspend fun downloadDirect(url: String, fileName: String, ext: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = true
            conn.connect()
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return@withContext "Error: HTTP $code" }
            val length = conn.contentLengthLong
            if (length == 0L) { conn.disconnect(); return@withContext "Error: archivo vacío" }

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.$ext")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/$ext")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    if (length > 0) put(MediaStore.Downloads.SIZE, length)
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext "Error"
                val output = requireContext().contentResolver.openOutputStream(uri) ?: return@withContext "Error"
                conn.inputStream.use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                requireContext().contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                dir.mkdirs()
                val file = File(dir, "$fileName.$ext")
                conn.inputStream.use { input -> FileOutputStream(file).use { out -> input.copyTo(out, bufferSize = 65536) } }
            }
            conn.disconnect()
            null
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
