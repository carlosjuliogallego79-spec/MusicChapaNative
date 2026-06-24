package com.musicchapa.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musicchapa.R
import java.io.File

class SongAdapter(
    private val songs: MutableList<String>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.song_title)
        val detail: TextView = view.findViewById(R.id.song_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = songs[position]
        val name = File(path).name
        holder.title.text = name.removeSuffix(".mp3")
        val size = File(path).length() / (1024 * 1024)
        holder.detail.text = "${size} MB"
        holder.itemView.findViewById<android.widget.ImageButton>(R.id.delete_btn).setOnClickListener {
            onDelete(path)
        }
    }

    override fun getItemCount() = songs.size
}
