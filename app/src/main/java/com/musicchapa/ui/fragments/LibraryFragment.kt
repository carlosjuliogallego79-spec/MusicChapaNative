package com.musicchapa.ui.fragments

import android.os.Bundle
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_library, container, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.song_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = SongAdapter(songs) { path ->
            File(path).delete()
            loadSongs()
        }
        loadSongs()
        return view
    }

    private fun loadSongs() {
        songs.clear()
        val dir = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
        if (dir.exists()) {
            songs.addAll(dir.listFiles()?.map { it.absolutePath } ?: emptyList())
        }
    }
}
