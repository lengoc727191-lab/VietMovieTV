package com.vietmovietv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        holder.tvName.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val tv = v as TextView
            if (hasFocus) {
                tv.setTextColor(0xFF000000.toInt())
                v.elevation = 10f
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(130L).start()
            } else {
                tv.setTextColor(ContextCompat.getColor(v.context, R.color.text_primary))
                v.elevation = 0f
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100L).start()
            }
        }
    }

    override fun getItemCount(): Int = episodes.size

    class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvEpisodeName)
    }
}
