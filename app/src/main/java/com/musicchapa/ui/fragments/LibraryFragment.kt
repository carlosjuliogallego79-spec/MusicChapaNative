package com.musicchapa.ui.fragments

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.SongItem
import com.musicchapa.ui.adapters.SongAdapter
import java.io.File

class LibraryFragment : Fragment() {

    private val songs = mutableListOf<SongItem>()
    private lateinit var adapter: SongAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaying: SongItem? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_library, container, false)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.song_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = SongAdapter(
            songs,
            onPlay = { item -> playSong(item, view) },
            onDelete = { item -> deleteSong(item, view) }
        )
        recycler.adapter = adapter

        view.findViewById<ImageButton>(R.id.btn_play).setOnClickListener {
            togglePlayPause(view)
        }

        loadSongs()
        return view
    }

    private fun loadSongs() {
        songs.clear()

        val privateDir = File(requireContext().getExternalFilesDir(null), "MusicChapa/downloads")
        if (privateDir.exists()) {
            privateDir.listFiles()?.forEach { file ->
                if (file.extension in listOf("mp3", "m4a", "opus", "wav")) {
                    songs.add(SongItem(file.absolutePath, file.nameWithoutExtension, file.length()))
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATA,
                MediaStore.Downloads.SIZE
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%MusicChapa%")
            val cursor = requireContext().contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, args, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val path = it.getString(2) ?: continue
                    val name = it.getString(1) ?: ""
                    val size = it.getLong(3)
                    if (name.endsWith(".mp3") && !songs.any { s -> s.path == path }) {
                        songs.add(SongItem(path, name.removeSuffix(".mp3"), size))
                    }
                }
            }
        } else {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MusicChapa")
            if (publicDir.exists()) {
                publicDir.listFiles()?.forEach { file ->
                    if (file.extension in listOf("mp3", "m4a", "opus", "wav")) {
                        val path = file.absolutePath
                        if (!songs.any { it.path == path }) {
                            songs.add(SongItem(path, file.nameWithoutExtension, file.length()))
                        }
                    }
                }
            }
        }

        songs.sortByDescending { it.size }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun playSong(item: SongItem, view: View) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(item.path)
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_play)
                    currentPlaying = null
                }
                prepareAsync()
            }
            currentPlaying = item
            val bar = view.findViewById<View>(R.id.player_bar)
            bar?.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.player_title)?.text = item.title
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_pause)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al reproducir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause(view: View) {
        val player = mediaPlayer
        if (player == null && currentPlaying != null) {
            currentPlaying?.let { playSong(it, view) }
            return
        }
        if (player == null) return

        if (player.isPlaying) {
            player.pause()
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_play)
        } else {
            player.start()
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun deleteSong(item: SongItem, view: View) {
        try {
            if (currentPlaying?.path == item.path) {
                mediaPlayer?.release()
                mediaPlayer = null
                currentPlaying = null
                view.findViewById<View>(R.id.player_bar)?.visibility = View.GONE
            }
            File(item.path).delete()
            if (Build.VERSION.SDK_INT >= 29) {
                val where = "${MediaStore.Downloads.DATA}=?"
                val args = arrayOf(item.path)
                requireContext().contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args)
            }
            loadSongs()
        } catch (e: Exception) {
            Toast.makeText(context, "Error al eliminar", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
