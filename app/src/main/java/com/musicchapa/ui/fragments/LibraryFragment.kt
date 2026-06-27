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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicchapa.R
import com.musicchapa.ui.adapters.SongItem
import com.musicchapa.ui.adapters.SongAdapter
import kotlinx.coroutines.*
import java.io.File

class LibraryFragment : Fragment() {

    private val songs = mutableListOf<SongItem>()
    private lateinit var adapter: SongAdapter
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaying: SongItem? = null
    private var currentIndex = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var seekUpdateJob: Job? = null

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

        view.findViewById<ImageButton>(R.id.btn_play).setOnClickListener { togglePlayPause(view) }
        view.findViewById<ImageButton>(R.id.btn_next).setOnClickListener { playNext(view) }
        view.findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { playPrev(view) }

        view.findViewById<SeekBar>(R.id.seek_bar).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let { mp ->
                        val pos = (mp.duration * progress) / 1000
                        mp.seekTo(pos)
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        loadSongs()
        return view
    }

    override fun onResume() {
        super.onResume()
        loadSongs()
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
            seekUpdateJob?.cancel()

            val idx = songs.indexOfFirst { it.path == item.path }
            if (idx >= 0) currentIndex = idx

            mediaPlayer = MediaPlayer().apply {
                setDataSource(item.path)
                setOnPreparedListener {
                    start()
                    updatePlayerUI(view, item)
                    startSeekUpdate(view)
                }
                setOnCompletionListener {
                    seekUpdateJob?.cancel()
                    view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_play)
                    // Auto-play next
                    playNext(view)
                }
                prepareAsync()
            }
            currentPlaying = item
            val bar = view.findViewById<View>(R.id.player_bar)
            bar?.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.player_title)?.text = item.title
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_pause)
            view.findViewById<SeekBar>(R.id.seek_bar)?.progress = 0
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePlayerUI(view: View, item: SongItem) {
        val mp = mediaPlayer ?: return
        val total = mp.duration / 1000
        view.findViewById<TextView>(R.id.time_total)?.text = formatTime(total)
        view.findViewById<TextView>(R.id.time_current)?.text = "0:00"
        view.findViewById<SeekBar>(R.id.seek_bar)?.max = 1000
    }

    private fun startSeekUpdate(view: View) {
        seekUpdateJob?.cancel()
        seekUpdateJob = scope.launch {
            while (isActive) {
                delay(200)
                val mp = mediaPlayer ?: break
                if (!mp.isPlaying) continue
                try {
                    val pos = mp.currentPosition
                    val dur = mp.duration
                    if (dur > 0) {
                        val progress = (pos * 1000) / dur
                        view.findViewById<SeekBar>(R.id.seek_bar)?.progress = progress
                        view.findViewById<TextView>(R.id.time_current)?.text = formatTime(pos / 1000)
                    }
                } catch (_: Exception) { break }
            }
        }
    }

    private fun playNext(view: View) {
        if (currentIndex < 0 || songs.isEmpty()) return
        val nextIdx = (currentIndex + 1) % songs.size
        val nextSong = songs.getOrNull(nextIdx) ?: return
        currentIndex = nextIdx
        playSong(nextSong, view)
    }

    private fun playPrev(view: View) {
        if (currentIndex < 0 || songs.isEmpty()) return
        val prevIdx = if (currentIndex - 1 < 0) songs.size - 1 else currentIndex - 1
        val prevSong = songs.getOrNull(prevIdx) ?: return
        currentIndex = prevIdx
        playSong(prevSong, view)
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
            seekUpdateJob?.cancel()
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_play)
        } else {
            player.start()
            startSeekUpdate(view)
            view.findViewById<ImageButton>(R.id.btn_play)?.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun deleteSong(item: SongItem, view: View) {
        try {
            if (currentPlaying?.path == item.path) {
                mediaPlayer?.release()
                mediaPlayer = null
                currentPlaying = null
                seekUpdateJob?.cancel()
                view.findViewById<View>(R.id.player_bar)?.visibility = View.GONE
            }
            // Update index if playing song was before the deleted one
            val delIdx = songs.indexOfFirst { it.path == item.path }
            if (delIdx >= 0 && delIdx < currentIndex) currentIndex--

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

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${if (s < 10) "0" else ""}$s"
    }

    override fun onDestroy() {
        seekUpdateJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        scope.cancel()
        super.onDestroy()
    }
}
