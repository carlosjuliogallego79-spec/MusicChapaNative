package com.musicchapa.ui.fragments

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.SongAdapter
import java.io.File

class LibraryFragment : Fragment() {

    private val songs = mutableListOf<String>()
    private lateinit var adapter: SongAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_library, container, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.song_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = SongAdapter(songs) { path ->
            File(path).delete()
            loadSongs()
        }
        recycler.adapter = adapter
        loadSongs()
        return view
    }

    private fun loadSongs() {
        songs.clear()
        val internal = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
        if (internal.exists()) {
            songs.addAll(internal.listFiles()?.map { it.absolutePath } ?: emptyList())
        }
        if (Build.VERSION.SDK_INT < 29) {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
            if (publicDir.exists()) {
                songs.addAll(publicDir.listFiles()?.map { it.absolutePath } ?: emptyList())
            }
        }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }
}
