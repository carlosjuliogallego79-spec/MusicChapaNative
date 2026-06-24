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
import androidx.fragment.app.Fragment
import com.musicchapa.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    data class AudioFormat(val itag: Int, val label: String, val ext: String, val bitrate: Int)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)
        val formatSpinner = view.findViewById<android.widget.Spinner>(R.id.format_spinner)

        formatSpinner.visibility = View.GONE

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val videoId = extractYoutubeId(url)
            if (videoId != null) {
                urlInput.text.clear()
                statusText.text = "Obteniendo info del video..."
                scope.launch { fetchFormats(videoId, statusText, formatSpinner) }
            } else {
                urlInput.text.clear()
                statusText.text = "Descargando..."
                scope.launch {
                    val result = downloadDirect(url, "song_${System.currentTimeMillis()}")
                    statusText.text = result ?: "Completa ✓"
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

    private suspend fun fetchFormats(videoId: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        currentFormats.clear()

        val json = withContext(Dispatchers.IO) { getVideoJson(videoId) }
        if (json == null) {
            statusText.text = "Error: no se pudo obtener info del video"
            return
        }

        val videoTitle = json.optString("title", "Video")
        val formats = json.optJSONArray("adaptiveFormats")
        if (formats == null) {
            statusText.text = "Error: sin formatos disponibles"
            return
        }

        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val mime = f.optString("type", "")
            if (!mime.startsWith("audio")) continue
            val itag = f.optInt("itag", 0)
            val bitrate = f.optInt("bitrate", 0)
            val (label, ext) = when (itag) {
                140 -> "M4A 128kbps AAC" to "m4a"
                251 -> "Opus 160kbps" to "opus"
                250 -> "Opus 70kbps" to "opus"
                249 -> "Opus 50kbps" to "opus"
                171 -> "WebM 128kbps Vorbis" to "webm"
                else -> "$itag ${bitrate / 1000}kbps" to "m4a"
            }
            currentFormats.add(AudioFormat(itag, "$label - $videoTitle", ext, bitrate))
        }

        if (currentFormats.isEmpty()) {
            statusText.text = "Error: sin formatos de audio"
            return
        }

        currentFormats.sortByDescending { it.bitrate }
        currentVideoId = videoId

        val labels = currentFormats.map { it.label }
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.visibility = View.VISIBLE
        statusText.text = "Seleccioná formato y tocalo"

        spinner.onItemSelectedListener = null
        spinner.setSelection(0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val fmt = currentFormats[pos]
                statusText.text = "Descargando ${fmt.label.take(30)}..."
                scope.launch {
                    val result = downloadAudio(videoId, fmt.itag, videoTitle, fmt.ext)
                    statusText.text = result ?: "Completa ✓"
                    if (result == null) spinner.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private suspend fun getVideoJson(videoId: String): JSONObject? {
        val instances = listOf(
            "https://inv.nadeko.net/api/v1/videos/$videoId",
            "https://invidious.snopyta.org/api/v1/videos/$videoId",
            "https://yewtu.be/api/v1/videos/$videoId"
        )
        for (url in instances) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android)")
                    .build()
                val body = client.newCall(req).execute().body?.string() ?: continue
                return JSONObject(body)
            } catch (_: Exception) { continue }
        }
        return null
    }

    private suspend fun downloadAudio(videoId: String, itag: Int, title: String, ext: String): String? = withContext(Dispatchers.IO) {
        val ctx = requireContext()

        val dlUrls = listOf(
            "https://inv.nadeko.net/latest_version?id=$videoId&itag=$itag",
            "https://invidious.snopyta.org/latest_version?id=$videoId&itag=$itag",
            "https://yewtu.be/latest_version?id=$videoId&itag=$itag"
        )

        for (dlUrl in dlUrls) {
            try {
                val req = Request.Builder().url(dlUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                val response = client.newCall(req).execute()

                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                val body = response.body
                if (body == null) { response.close(); continue }
                val length = body.contentLength()

                if (length == 0L) {
                    response.close()
                    continue
                }

                val safeName = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(50)
                val fileName = "${safeName}_${videoId.take(8)}.$ext"
                var success = false

                val tempFile = File(ctx.cacheDir, fileName)
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
                response.close()

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    tempFile.delete()
                    continue
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "audio/${if (ext == "m4a") "mp4" else ext}")
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
                            success = true
                        }
                    }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                    dir.mkdirs()
                    tempFile.copyTo(File(dir, fileName), overwrite = true)
                    success = true
                }

                tempFile.delete()
                if (success) return@withContext null
            } catch (_: Exception) { continue }
        }
        return@withContext "Error: no se pudo descargar de ningún servidor"
    }

    private suspend fun downloadDirect(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            val response = client.newCall(req).execute()
            if (!response.isSuccessful) { response.close(); return@withContext "Error: HTTP ${response.code}" }
            val body = response.body ?: return@withContext "Error: sin respuesta"
            val length = body.contentLength()
            if (length == 0L) { response.close(); return@withContext "Error: archivo vacío" }

            val ext = url.substringAfterLast('.', "mp3").take(4)
            val tempFile = File(ctx.cacheDir, "$fileName.$ext")
            body.byteStream().use { input -> FileOutputStream(tempFile).use { out -> input.copyTo(out, bufferSize = 65536) } }
            response.close()

            if (!tempFile.exists() || tempFile.length() == 0L) { tempFile.delete(); return@withContext "Error: descarga vacía" }

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.$ext")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/$ext")
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
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                dir.mkdirs()
                tempFile.copyTo(File(dir, "$fileName.$ext"), overwrite = true)
            }
            tempFile.delete()
            null
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
