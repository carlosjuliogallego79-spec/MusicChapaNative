package com.musicchapa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.SongAdapter
import kotlinx.coroutines.*
import java.io.File
import java.net.URL

class UrlDownloadFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_url_download, container, false)
        val urlInput = view.findViewById<android.widget.EditText>(R.id.url_input)
        val statusText = view.findViewById<android.widget.TextView>(R.id.status_text)

        view.findViewById<android.widget.Button>(R.id.download_btn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            statusText.text = "Descargando..."
            scope.launch {
                val result = downloadFile(url)
                statusText.text = result ?: "Descarga completa"
            }
        }
        return view
    }

    private suspend fun downloadFile(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
            dir.mkdirs()
            val fileName = "song_${System.currentTimeMillis()}.mp3"
            val file = File(dir, fileName)
            URL(url).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            null // success
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
