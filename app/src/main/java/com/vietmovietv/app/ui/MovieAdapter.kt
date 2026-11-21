package com.vietmovietv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.R
import com.vietmovietv.app.data.MovieItem
import java.util.Locale

class MovieAdapter(
    private val onMovieClick: (MovieItem) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<MovieAdapter.MovieViewHolder>() {

    private val items = mutableListOf<MovieItem>()
    private val seenSlugs = HashSet<String>()

    fun setMovies(newItems: List<MovieItem>) {
        items.clear()
        seenSlugs.clear()
        newItems.forEach {
            if (seenSlugs.add(it.slug.lowercase())) {
                items.add(it)
            }
        }
        notifyDataSetChanged()
    }

    fun appendMovies(moreItems: List<MovieItem>) {
        val added = mutableListOf<MovieItem>()
        moreItems.forEach {
            if (seenSlugs.add(it.slug.lowercase())) {
                added.add(it)
            }
        }
        if (added.isNotEmpty()) {
            val start = items.size
            items.addAll(added)
            notifyItemRangeInserted(start, added.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovieViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        return MovieViewHolder(view)
    }

    override fun onBindViewHolder(holder: MovieViewHolder, position: Int) {
        val movie = items[position]
        holder.bind(movie)

        holder.itemView.setOnClickListener { onMovieClick(movie) }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.07f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            v.elevation = if (hasFocus) 12f else 0f

            if (hasFocus && position >= items.size - 10) {
                onLoadMore()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class MovieViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivPoster)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
        private val tvSourceBadge: TextView = itemView.findViewById(R.id.tvSourceBadge)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubTitle: TextView = itemView.findViewById(R.id.tvSubTitle)

        fun bind(movie: MovieItem) {
            tvTitle.text = movie.name.ifBlank { "Không tên" }
            val ratingText = if (movie.tmdbRating > 0.0) {
                String.format(Locale.US, "★ %.1f", movie.tmdbRating)
            } else ""
            tvSubTitle.text = listOf(movie.year, ratingText, movie.originName)
                .filter { it.isNotBlank() }
                .joinToString(" • ")

            val badgeText = movie.episodeCurrent.ifBlank { movie.quality }
            if (badgeText.isNotBlank()) {
                tvBadge.text = badgeText
                tvBadge.visibility = View.VISIBLE
            } else {
                tvBadge.visibility = View.GONE
            }

            if (movie.sourceLabel.isNotBlank()) {
                tvSourceBadge.text = movie.sourceLabel
                tvSourceBadge.visibility = View.VISIBLE
            } else {
                tvSourceBadge.visibility = View.GONE
            }

            val imgUrl = movie.posterUrl.ifBlank { movie.thumbUrl }
            ivPoster.load(imgUrl) {
                crossfade(true)
            }
        }
    }
}
