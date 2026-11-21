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
import com.vietmovietv.app.data.resolveImageUrl

class MovieAdapter(
    private val onMovieClick: (MovieItem) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<MovieAdapter.MovieViewHolder>() {

    private val items = mutableListOf<MovieItem>()
    private var cdnDomain: String = "https://phimimg.com"

    fun setMovies(newItems: List<MovieItem>, cdn: String = "https://phimimg.com") {
        cdnDomain = cdn
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendMovies(moreItems: List<MovieItem>, cdn: String = "https://phimimg.com") {
        cdnDomain = cdn
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MovieViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
        return MovieViewHolder(view)
    }

    override fun onBindViewHolder(holder: MovieViewHolder, position: Int) {
        val movie = items[position]
        holder.bind(movie, cdnDomain)

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
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubTitle: TextView = itemView.findViewById(R.id.tvSubTitle)

        fun bind(movie: MovieItem, cdnDomain: String) {
            tvTitle.text = movie.name ?: "Không tên"
            val yearText = movie.year?.takeIf { it.isNotBlank() } ?: ""
            val originText = movie.originName?.takeIf { it.isNotBlank() } ?: ""
            tvSubTitle.text = listOf(yearText, originText).filter { it.isNotBlank() }.joinToString(" • ")

            val badgeText = movie.episodeCurrent?.takeIf { it.isNotBlank() }
                ?: movie.quality?.takeIf { it.isNotBlank() }
            if (!badgeText.isNullOrBlank()) {
                tvBadge.text = badgeText
                tvBadge.visibility = View.VISIBLE
            } else {
                tvBadge.visibility = View.GONE
            }

            val imgUrl = resolveImageUrl(
                movie.posterUrl?.takeIf { it.isNotBlank() } ?: movie.thumbUrl,
                cdnDomain
            )
            ivPoster.load(imgUrl) {
                crossfade(true)
            }
        }
    }
}
