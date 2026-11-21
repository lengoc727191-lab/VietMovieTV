package com.vietmovietv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.R
import com.vietmovietv.app.data.MovieItem
import java.util.Locale

class MovieAdapter(
    private val onMovieClick: (MovieItem) -> Unit,
    private val onMovieFocused: (MovieItem) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<MovieAdapter.MovieViewHolder>() {

    private val items = mutableListOf<MovieItem>()
    private val seenSlugs = HashSet<String>()
    private val focusInInterpolator = OvershootInterpolator(1.1f)
    private val focusOutInterpolator = DecelerateInterpolator()

    fun setMovies(newItems: List<MovieItem>) {
        val deduped = mutableListOf<MovieItem>()
        val freshSlugs = HashSet<String>()
        newItems.forEach {
            if (freshSlugs.add(it.slug.lowercase())) {
                deduped.add(it)
            }
        }
        val oldList = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = deduped.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos].slug == deduped[newPos].slug
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == deduped[newPos]
        })
        items.clear()
        items.addAll(deduped)
        seenSlugs.clear()
        seenSlugs.addAll(freshSlugs)
        diff.dispatchUpdatesTo(this)
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
            v.animate().cancel()
            if (hasFocus) {
                v.elevation = 20f
                v.translationZ = 14f
                v.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(180L)
                    .setInterpolator(focusInInterpolator)
                    .start()
                onMovieFocused(movie)
                if (position >= items.size - 10) {
                    onLoadMore()
                }
            } else {
                v.elevation = 0f
                v.translationZ = 0f
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(130L)
                    .setInterpolator(focusOutInterpolator)
                    .start()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class MovieViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivPoster)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBadge)
        private val tvDubBadge: TextView = itemView.findViewById(R.id.tvDubBadge)
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

            val dubTag = movie.getDubShortTag()
            if (dubTag.isNotEmpty()) {
                tvDubBadge.text = dubTag
                tvDubBadge.visibility = View.VISIBLE
            } else {
                tvDubBadge.visibility = View.GONE
            }

            if (movie.sourceLabel.isNotBlank()) {
                tvSourceBadge.text = movie.sourceLabel
                tvSourceBadge.visibility = View.VISIBLE
            } else {
                tvSourceBadge.visibility = View.GONE
            }

            val imgUrl = movie.posterUrl.ifBlank { movie.thumbUrl }
            ivPoster.load(imgUrl) {
                crossfade(220)
                size(320, 460)
            }
        }
    }
}
