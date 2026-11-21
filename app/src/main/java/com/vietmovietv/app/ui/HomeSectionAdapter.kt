package com.vietmovietv.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.R
import com.vietmovietv.app.data.MovieItem

data class HomeSection(
    val title: String,
    val movies: List<MovieItem>
)

class HomeSectionAdapter(
    private val onMovieClick: (MovieItem) -> Unit,
    private val onMovieFocused: (MovieItem) -> Unit
) : RecyclerView.Adapter<HomeSectionAdapter.SectionViewHolder>() {

    private val sections = mutableListOf<HomeSection>()
    private val sharedPool = RecyclerView.RecycledViewPool()

    fun submitSections(newSections: List<HomeSection>) {
        sections.clear()
        sections.addAll(newSections.filter { it.movies.isNotEmpty() })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_section, parent, false)
        return SectionViewHolder(view, sharedPool)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        val sec = sections[position]
        holder.tvTitle.text = sec.title
        holder.rvMovies.adapter = HorizontalMovieAdapter(sec.movies, onMovieClick, onMovieFocused)
    }

    override fun getItemCount(): Int = sections.size

    class SectionViewHolder(itemView: View, pool: RecyclerView.RecycledViewPool) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvSectionTitle)
        val rvMovies: RecyclerView = itemView.findViewById(R.id.rvSectionMovies)

        init {
            rvMovies.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rvMovies.setRecycledViewPool(pool)
            rvMovies.setHasFixedSize(true)
            rvMovies.setItemViewCacheSize(10)
        }
    }
}

class HorizontalMovieAdapter(
    private val items: List<MovieItem>,
    private val onMovieClick: (MovieItem) -> Unit,
    private val onMovieFocused: (MovieItem) -> Unit
) : RecyclerView.Adapter<HorizontalMovieAdapter.CardVH>() {

    private val focusIn = OvershootInterpolator(1.1f)
    private val focusOut = DecelerateInterpolator()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_movie_netflix, parent, false)
        return CardVH(v)
    }

    override fun onBindViewHolder(holder: CardVH, position: Int) {
        val movie = items[position]
        holder.bind(movie)

        holder.itemView.setOnClickListener { onMovieClick(movie) }
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            if (hasFocus) {
                v.elevation = 20f
                v.translationZ = 14f
                v.animate().scaleX(1.09f).scaleY(1.09f).setDuration(170L).setInterpolator(focusIn).start()
                onMovieFocused(movie)
            } else {
                v.elevation = 0f
                v.translationZ = 0f
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).setInterpolator(focusOut).start()
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class CardVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.ivPoster)
        private val tvDubBadge: TextView = itemView.findViewById(R.id.tvDubBadge)
        private val tvEpBadge: TextView = itemView.findViewById(R.id.tvEpBadge)

        fun bind(movie: MovieItem) {
            val dub = movie.getDubShortTag()
            if (dub.isNotEmpty()) {
                tvDubBadge.text = dub
                tvDubBadge.visibility = View.VISIBLE
            } else {
                tvDubBadge.visibility = View.GONE
            }

            val ep = movie.episodeCurrent.ifBlank { movie.quality }
            if (ep.isNotBlank()) {
                tvEpBadge.text = ep
                tvEpBadge.visibility = View.VISIBLE
            } else {
                tvEpBadge.visibility = View.GONE
            }

            val imgUrl = movie.posterUrl.ifBlank { movie.thumbUrl }
            ivPoster.load(imgUrl) {
                crossfade(200)
                size(280, 400)
            }
        }
    }
}
