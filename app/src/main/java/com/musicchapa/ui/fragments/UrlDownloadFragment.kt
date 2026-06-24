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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentFormats = mutableListOf<AudioFormat>()
    private var currentUrl = ""

    data class AudioFormat(val formatId: String, val label: String)

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
            statusText.text = "Analizando enlace..."
            scope.launch { analyzeUrl(url, statusText, formatSpinner) }
        }
        return view
    }

    private suspend fun analyzeUrl(url: String, statusText: android.widget.TextView, spinner: android.widget.Spinner) {
        currentFormats.clear()
        try {
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-warnings")
            request.addOption("--no-check-certificate")
            request.addOption("--user-agent", "Mozilla/5.0")
            request.addOption("--skip-download")
            request.addOption("--print-json")

            val response = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request)
            }

            val json = JSONObject(response.out)
            val title = json.optString("title", "Video")

            // Always offer MP3 format
            currentFormats.add(AudioFormat("bestaudio/best", "MP3 192kbps (recomendado)"))
            currentFormats.add(AudioFormat("bestaudio", "MP3 mejor calidad"))

            val labels = currentFormats.map { "${it.label} - ${title.take(50)}" }
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.visibility = View.VISIBLE
            statusText.text = "Seleccioná formato para descargar"

            spinner.onItemSelectedListener = null
            spinner.setSelection(0)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val fmt = currentFormats[pos]
                    statusText.text = "Descargando ${fmt.label}..."
                    scope.launch {
                        val result = downloadMp3(url, fmt.formatId, title, statusText)
                        statusText.text = result ?: "Completa ✓"
                        spinner.visibility = View.GONE
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private suspend fun downloadMp3(url: String, formatId: String, title: String, statusText: android.widget.TextView): String? = withContext(Dispatchers.IO) {
        try {
            val ctx = requireContext()
            val dir = File(ctx.cacheDir, "ytdlp")
            dir.mkdirs()

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-warnings")
            request.addOption("--no-check-certificate")
            request.addOption("--user-agent", "Mozilla/5.0")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "0")
            request.addOption("-o", "${dir.absolutePath}/%(title)s.%(ext)s")
            request.addOption("-f", formatId)

            YoutubeDL.getInstance().execute(request)

            val files = dir.listFiles()
            val mp3 = files?.filter { it.extension == "mp3" }?.maxByOrNull { it.lastModified() }
            if (mp3 == null || !mp3.exists() || mp3.length() == 0L) {
                return@withContext "Error: archivo MP3 no generado"
            }

            val safeName = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(50)
            val fileName = "${safeName}_${System.currentTimeMillis()}.mp3"

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MusicChapa")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                    put(MediaStore.Downloads.SIZE, mp3.length())
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val output = ctx.contentResolver.openOutputStream(uri)
                    if (output != null) {
                        mp3.inputStream().use { input -> output.use { out -> input.copyTo(out, bufferSize = 65536) } }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        ctx.contentResolver.update(uri, values, null, null)
                        mp3.delete()
                        return@withContext null
                    }
                }
                mp3.delete()
                return@withContext "Error: no se pudo guardar en descargas"
            } else {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
                publicDir.mkdirs()
                mp3.copyTo(File(publicDir, fileName), overwrite = true)
                mp3.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
