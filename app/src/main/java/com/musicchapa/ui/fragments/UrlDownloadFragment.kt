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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
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
    private var currentFormats = mutableListOf<AudioFormat>()
    private var currentUrl = ""

    data class AudioFormat(val itag: Int, val label: String, val bitrate: Int)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)
        val formatSpinner = view.findViewById<android.widget.Spinner>(R.id.format_spinner)
        formatSpinner.visibility = View.GONE

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            urlInput.text.clear()
            currentUrl = url
            statusText.text = "Analizando..."
            scope.launch { analyzeUrl(url, statusText, formatSpinner) }
        }
        return view
    }

    private suspend fun analyzeUrl(url: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        currentFormats.clear()
        val videoId = extractYoutubeId(url)
        if (videoId == null) {
            statusText.text = "Error: link de YouTube no válido"
            return
        }

        val formats = withContext(Dispatchers.IO) { getAudioFormats(videoId) }
        if (formats == null) {
            statusText.text = "Error: no se pudieron obtener formatos"
            return
        }

        currentFormats.addAll(formats)
        if (currentFormats.isEmpty()) {
            statusText.text = "Error: sin formatos de audio"
            return
        }

        val labels = currentFormats.map { it.label }
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.visibility = View.VISIBLE
        statusText.text = "Seleccioná formato"

        spinner.onItemSelectedListener = null
        spinner.setSelection(0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val fmt = currentFormats[pos]
                statusText.text = "Descargando..."
                scope.launch {
                    val result = downloadAudio(videoId, fmt.itag)
                    statusText.text = if (result == null) "Completa ✓" else "Error: $result"
                    spinner.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

    private fun getAudioFormats(videoId: String): List<AudioFormat>? {
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
            val jsonStr = response.body?.string() ?: return null
            val json = JSONObject(jsonStr)

            val streamingData = json.optJSONObject("streamingData") ?: return null
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return null

            val result = mutableListOf<AudioFormat>()
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val mime = f.optString("mimeType", "")
                if (!mime.startsWith("audio")) continue
                val itag = f.optInt("itag", 0)
                val bitrate = f.optInt("bitrate", 0)
                val label = when (itag) {
                    140 -> "M4A 128kbps"
                    251 -> "Opus 160kbps"
                    250 -> "Opus 70kbps"
                    249 -> "Opus 50kbps"
                    else -> "${bitrate / 1000}kbps"
                }
                result.add(AudioFormat(itag, label, bitrate))
            }
            result.sortByDescending { it.bitrate }
            return result
        } catch (_: Exception) { return null }
    }

    private suspend fun downloadAudio(videoId: String, itag: Int): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()

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
            val jsonStr = response.body?.string() ?: return@withContext "Error: sin respuesta"
            val json = JSONObject(jsonStr)

            val streamingData = json.optJSONObject("streamingData") ?: return@withContext "Error: sin streaming data"
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return@withContext "Error: sin formatos"

            var audioUrl: String? = null
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                if (f.optInt("itag", 0) == itag || (itag == 0 && f.optString("mimeType", "").startsWith("audio"))) {
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
                    if (!audioUrl.isNullOrEmpty()) break
                }
            }

            if (audioUrl.isNullOrEmpty()) {
                return@withContext "Error: URL de audio no encontrada"
            }

            val ext = if (itag == 140 || itag == 0) "m4a" else "opus"
            val tempFile = File(ctx.cacheDir, "audio_${videoId.take(8)}.$ext")

            val audioRequest = Request.Builder().url(audioUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .header("Referer", "https://www.youtube.com/")
                .header("Origin", "https://www.youtube.com")
                .build()
            val audioResponse = client.newCall(audioRequest).execute()
            if (!audioResponse.isSuccessful) {
                audioResponse.close()
                return@withContext "Error HTTP: ${audioResponse.code}"
            }
            val body = audioResponse.body ?: return@withContext "Error: sin body"
            val length = body.contentLength()
            if (length == 0L) {
                audioResponse.close()
                return@withContext "Error: archivo vacío"
            }

            body.byteStream().use { input ->
                tempFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = 65536)
                }
            }
            audioResponse.close()

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext "Error: descarga vacía"
            }

            val fileName = "${videoId.take(8)}_${System.currentTimeMillis()}.$ext"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, if (ext == "m4a") "audio/mp4" else "audio/opus")
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
                        return@withContext null
                    }
                }
                return@withContext "Error: no se pudo guardar"
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                dir.mkdirs()
                tempFile.copyTo(File(dir, fileName), overwrite = true)
                tempFile.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext e.message ?: "Error desconocido"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
