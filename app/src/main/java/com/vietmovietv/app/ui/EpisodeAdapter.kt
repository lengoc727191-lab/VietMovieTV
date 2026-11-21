package com.vietmovietv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vietmovietv.app.R
import com.vietmovietv.app.data.EpisodeData

class EpisodeAdapter(
    private val onEpisodeClick: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    private val episodes = mutableListOf<EpisodeData>()
    private var selectedIndex = -1

    fun submitList(list: List<EpisodeData>, activeIndex: Int) {
        episodes.clear()
        episodes.addAll(list)
        selectedIndex = activeIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val ep = episodes[position]
        holder.tvName.text = ep.name.ifBlank { "Tập ${position + 1}" }
        holder.tvName.isSelected = (position == selectedIndex)

        holder.tvName.setOnClickListener {
            onEpisodeClick(position)
        }
        FocusEffects.attachPopEffect(holder.tvName, scaleUp = 1.09f, elevationPx = 12f)
    }

    override fun getItemCount(): Int = episodes.size

    class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvEpisodeName)
    }
}
