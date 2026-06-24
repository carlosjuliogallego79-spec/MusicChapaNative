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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    private suspend fun downloadFile(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val urlObj = URL(url)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
            conn.setRequestProperty("Referer", "https://www.youtube.com/")
            conn.instanceFollowRedirects = true
            conn.connect()

            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext "Error: HTTP $code"
            }
            val length = conn.contentLengthLong
            if (length == 0L) {
                conn.disconnect()
                return@withContext "Error: archivo vacío"
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.m4a")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    if (length > 0) put(MediaStore.Downloads.SIZE, length)
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext "Error: no se pudo crear el archivo"

                val output = requireContext().contentResolver.openOutputStream(uri)
                    ?: return@withContext "Error: no se pudo abrir el archivo"

                val total = conn.inputStream.use { input ->
                    output.use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (total > 0) values.put(MediaStore.Downloads.SIZE, total)
                requireContext().contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "MusicChapa"
                )
                dir.mkdirs()
                val file = File(dir, "$fileName.m4a")
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
            }
            conn.disconnect()
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
