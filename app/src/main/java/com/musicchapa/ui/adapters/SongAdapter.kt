package com.musicchapa.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musicchapa.R
import java.io.File

data class SongItem(val path: String, val title: String, val size: Long)

class SongAdapter(
    private val songs: MutableList<SongItem>,
    private val onPlay: (SongItem) -> Unit,
    private val onDelete: (SongItem) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.song_title)
        val detail: TextView = view.findViewById(R.id.song_detail)
        val playBtn: ImageButton = view.findViewById(R.id.play_btn)
        val deleteBtn: ImageButton = view.findViewById(R.id.delete_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = songs[position]
        holder.title.text = item.title
        holder.detail.text = formatSize(item.size)
        holder.playBtn.setOnClickListener { onPlay(item) }
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = songs.size

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}
