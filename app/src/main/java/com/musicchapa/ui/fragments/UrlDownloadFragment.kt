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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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

    data class AudioFormat(val formatId: String, val label: String, val url: String?)

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
            statusText.text = "Analizando..."
            scope.launch { analyzeUrl(url, statusText, formatSpinner) }
        }
        return view
    }

    private suspend fun analyzeUrl(url: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        currentFormats.clear()

        val (json, title) = getVideoJson(url)
        if (json == null) {
            statusText.text = "Error: no se pudo obtener info"
            return
        }

        val formats = json.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                if (vcodec == "none" && acodec != "none") {
                    val urlStr = f.optString("url")
                    val formatId = f.optString("format_id", "")
                    val abr = f.optInt("abr", f.optInt("tbr", 0))
                    val ext = f.optString("ext", "m4a")
                    val label = "$ext ${abr}kbps"
                    currentFormats.add(AudioFormat(formatId, label, urlStr.ifEmpty { null }))
                }
            }
        }

        currentFormats.add(AudioFormat("bestaudio/best", "MP3 (convertido)", null))

        if (currentFormats.isEmpty()) {
            statusText.text = "Error: sin formatos disponibles"
            return
        }

        val labels = currentFormats.map { "${it.label} - $title" }
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
                    val result = if (fmt.url != null) {
                        downloadDirect(fmt.url, ext = fmt.label.substringBefore(" "))
                    } else {
                        downloadWithYtdlp(url)
                    }
                    statusText.text = if (result == null) "Completa ✓" else "Error: $result"
                    spinner.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private suspend fun getVideoJson(url: String): Pair<JSONObject?, String> {
        try {
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            req.addOption("--no-check-certificate")
            req.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            req.addOption("--skip-download")
            req.addOption("--print-json")

            val resp = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(req)
            }
            val json = JSONObject(resp.out)
            val title = json.optString("title", "Audio")
            return Pair(json, title)
        } catch (e: Exception) {
            return Pair(null, "Error: ${e.message}")
        }
    }

    private suspend fun downloadDirect(url: String, ext: String = "m4a"): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                .header("Referer", "https://www.youtube.com/")
                .header("Origin", "https://www.youtube.com")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return@withContext "HTTP ${resp.code}" }
            val body = resp.body ?: return@withContext "sin body"
            if (body.contentLength() == 0L) { resp.close(); return@withContext "vacío" }

            val tempFile = File(ctx.cacheDir, "audio_${System.currentTimeMillis()}.$ext")
            body.byteStream().use { input -> tempFile.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) } }
            resp.close()

            if (!tempFile.exists() || tempFile.length() == 0L) { tempFile.delete(); return@withContext "descarga vacía" }

            saveToDownloads(tempFile, ext)
            return@withContext null
        } catch (e: Exception) { return@withContext e.message }
    }

    private suspend fun downloadWithYtdlp(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()
            val dir = File(ctx.cacheDir, "ytdlp")
            dir.mkdirs()

            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            req.addOption("--no-check-certificate")
            req.addOption("--user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            req.addOption("--extract-audio")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
            req.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
            req.addOption("-f", "bestaudio/best")

            YoutubeDL.getInstance().execute(req)

            val mp3 = dir.listFiles()?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
            if (mp3 == null || !mp3.exists() || mp3.length() == 0L) return@withContext "no se generó MP3"

            saveToDownloads(mp3, "mp3")
            return@withContext null
        } catch (e: Exception) {
            return@withContext e.message ?: "error yt-dlp"
        }
    }

    private fun saveToDownloads(file: File, ext: String) {
        val ctx = requireContext()
        val fileName = "audio_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, if (ext == "mp3") "audio/mpeg" else "audio/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.SIZE, file.length())
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val output = ctx.contentResolver.openOutputStream(uri)
                if (output != null) {
                    file.inputStream().use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                }
            }
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
            publicDir.mkdirs()
            file.copyTo(File(publicDir, fileName), overwrite = true)
        }
        file.delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
