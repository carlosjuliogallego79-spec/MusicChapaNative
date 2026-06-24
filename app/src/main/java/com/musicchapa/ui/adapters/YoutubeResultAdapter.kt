package com.musicchapa.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musicchapa.R

class YoutubeResultAdapter(
    private val results: List<Pair<String, String>>,
    private val onDownload: (String) -> Unit
) : RecyclerView.Adapter<YoutubeResultAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.Adapter.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.song_title)
        val artist: TextView = view.findViewById(R.id.song_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (title, videoId) = results[position]
        holder.title.text = title
        holder.artist.text = "YouTube"
        holder.itemView.setOnClickListener { onDownload(videoId) }
    }

    override fun getItemCount() = results.size
}
