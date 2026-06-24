package com.musicchapa.ui.fragments

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.musicchapa.R
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentVideoId: String? = null
    private var currentFormats = mutableListOf<AudioFormat>()
    private var currentTitle = ""

    data class AudioFormat(val formatId: String, val label: String, val ext: String, val bitrate: Int)

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
            val videoId = extractYoutubeId(url)
            if (videoId != null) {
                statusText.text = "Obteniendo info..."
                scope.launch { fetchFormats(url, statusText, formatSpinner) }
            } else {
                statusText.text = "Descargando..."
                scope.launch { downloadWithYtDlp(url, "audio", statusText) }
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

    private suspend fun fetchFormats(url: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        currentFormats.clear()
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-warnings")
            request.addOption("--skip-download")
            request.addOption("--dump-json")

            val result = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().getInfo(request)
            }

            val json = JSONObject(result.out)
            currentTitle = json.optString("title", "Video")
            currentVideoId = json.optString("id", "")

            val formats = json.optJSONArray("formats") ?: JSONArray()
            val seen = mutableSetOf<String>()

            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")

                if (vcodec == "none" && acodec != "none") {
                    val formatId = f.optString("format_id", "")
                    val abr = f.optInt("abr", f.optInt("tbr", 0))
                    val ext = f.optString("ext", "m4a")
                    val label = "${ext.uppercase()} ${abr}kbps"
                    if (formatId !in seen) {
                        seen.add(formatId)
                        currentFormats.add(AudioFormat(formatId, label, ext, abr))
                    }
                }
            }

            if (currentFormats.isEmpty()) {
                currentFormats.add(AudioFormat("bestaudio/best", "MP3 192kbps (recomendado)", "mp3", 0))
            }

            currentFormats.sortByDescending { it.bitrate }
            val labels = currentFormats.map { "${it.label} - $currentTitle" }

            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.visibility = View.VISIBLE
            statusText.text = "Seleccioná formato para descargar MP3"

            spinner.onItemSelectedListener = null
            spinner.setSelection(0)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val fmt = currentFormats[pos]
                    statusText.text = "Descargando ${fmt.label}..."
                    scope.launch {
                        downloadWithYtDlp(url, fmt.formatId, statusText)
                        spinner.visibility = View.GONE
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private suspend fun downloadWithYtDlp(url: String, formatId: String, statusText: android.widget.TextView) {
        try {
            val ctx = requireContext()
            val dir = File(ctx.getExternalFilesDir(null), "ytdlp")
            dir.mkdirs()

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-warnings")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
            if (formatId != "bestaudio/best") {
                request.addOption("-f", "$formatId+bestaudio/best")
            }

            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request)
            }

            // Find the downloaded file
            val downloadedFile = dir.listFiles()
                ?.maxByOrNull { it.lastModified() }
            if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0) {
                moveToDownloads(downloadedFile)
                statusText.text = "Completa: ${downloadedFile.name}"
            } else {
                statusText.text = "Error: archivo no encontrado"
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun moveToDownloads(file: File) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
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
            file.copyTo(File(publicDir, file.name), overwrite = true)
        }
        file.delete()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
