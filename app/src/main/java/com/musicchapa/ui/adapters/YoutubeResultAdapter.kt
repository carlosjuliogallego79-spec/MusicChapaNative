package com.musicchapa.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.musicchapa.R
import com.musicchapa.ui.fragments.YoutubeFragment.YoutubeResult

class YoutubeResultAdapter(
    private val results: List<YoutubeResult>,
    private val onDownload: (String) -> Unit
) : RecyclerView.Adapter<YoutubeResultAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.song_title)
        val detail: TextView = view.findViewById(R.id.song_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_youtube_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        holder.title.text = result.title
        holder.detail.text = "${result.author} - ${formatDuration(result.duration)}"
        holder.itemView.setOnClickListener { onDownload(result.videoId) }
    }

    override fun getItemCount() = results.size

    private fun formatDuration(seconds: Long): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%d:%02d".format(min, sec)
    }
}
