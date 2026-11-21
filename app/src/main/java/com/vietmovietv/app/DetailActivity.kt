package com.vietmovietv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vietmovietv.app.data.EpisodeData
import com.vietmovietv.app.data.EpisodeServer
import com.vietmovietv.app.data.MovieApiService
import com.vietmovietv.app.data.WatchHistoryManager
import com.vietmovietv.app.data.resolveImageUrl
import com.vietmovietv.app.ui.EpisodeAdapter
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {

    private lateinit var ivBackdrop: ImageView
    private lateinit var ivDetailPoster: ImageView
    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailMeta: TextView
    private lateinit var tvDetailDesc: TextView
    private lateinit var btnPlayNow: Button
    private lateinit var serverContainer: LinearLayout
    private lateinit var tvEpisodeHeader: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var progressBar: ProgressBar

    private lateinit var historyManager: WatchHistoryManager
    private lateinit var episodeAdapter: EpisodeAdapter

    private var movieSlug: String = ""
    private var movieTitle: String = ""
    private var servers: List<EpisodeServer> = emptyList()
    private var currentServerIndex = 0
    private var currentEpisodes: List<EpisodeData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        movieSlug = intent.getStringExtra("slug") ?: ""
        movieTitle = intent.getStringExtra("title") ?: ""
        historyManager = WatchHistoryManager(this)

        ivBackdrop = findViewById(R.id.ivBackdrop)
        ivDetailPoster = findViewById(R.id.ivDetailPoster)
        tvDetailTitle = findViewById(R.id.tvDetailTitle)
        tvDetailMeta = findViewById(R.id.tvDetailMeta)
        tvDetailDesc = findViewById(R.id.tvDetailDesc)
        btnPlayNow = findViewById(R.id.btnPlayNow)
        serverContainer = findViewById(R.id.serverContainer)
        tvEpisodeHeader = findViewById(R.id.tvEpisodeHeader)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        progressBar = findViewById(R.id.detailProgressBar)

        tvDetailTitle.text = movieTitle

        episodeAdapter = EpisodeAdapter { epIndex ->
            openPlayer(currentServerIndex, epIndex)
        }
        rvEpisodes.layoutManager = GridLayoutManager(this, 8)
        rvEpisodes.adapter = episodeAdapter

        btnPlayNow.setOnClickListener {
            val savedEp = historyManager.getLastEpisodeIndex(movieSlug)
            val savedServer = historyManager.getLastServerIndex(movieSlug)
            val targetServer = if (savedServer in servers.indices) savedServer else currentServerIndex
            val eps = getPlayableEpisodes(targetServer)
            val targetEp = if (savedEp in eps.indices) savedEp else 0
            openPlayer(targetServer, targetEp)
        }

        if (movieSlug.isNotBlank()) {
            loadDetail(movieSlug)
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (servers.isNotEmpty()) {
            updateContinueButton()
            selectServer(currentServerIndex)
        }
    }

    private fun loadDetail(slug: String) {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val res = MovieApiService.instance.getMovieDetail(slug)
                val movie = res.movie
                if (movie != null) {
                    movieTitle = movie.name ?: movieTitle
                    tvDetailTitle.text = movieTitle

                    val metaParts = listOfNotNull(
                        movie.originName?.takeIf { it.isNotBlank() },
                        movie.year?.takeIf { it.isNotBlank() },
                        movie.quality?.takeIf { it.isNotBlank() },
                        movie.lang?.takeIf { it.isNotBlank() },
                        movie.episodeCurrent?.takeIf { it.isNotBlank() }
                    )
                    tvDetailMeta.text = metaParts.joinToString("  •  ")

                    val cleanDesc = HtmlCompat.fromHtml(
                        movie.content ?: "",
                        HtmlCompat.FROM_HTML_MODE_LEGACY
                    ).toString().trim()
                    tvDetailDesc.text = cleanDesc.ifBlank { "Chưa có mô tả cho phim này." }

                    ivDetailPoster.load(resolveImageUrl(movie.posterUrl)) { crossfade(true) }
                    ivBackdrop.load(resolveImageUrl(movie.thumbUrl?.takeIf { it.isNotBlank() } ?: movie.posterUrl)) {
                        crossfade(true)
                    }
                }

                servers = res.episodes?.filter { server ->
                    !server.serverData.isNullOrEmpty()
                } ?: emptyList()

                if (servers.isNotEmpty()) {
                    val savedServer = historyManager.getLastServerIndex(movieSlug)
                    currentServerIndex = if (savedServer in servers.indices) savedServer else 0
                    buildServerButtons()
                    selectServer(currentServerIndex)
                    updateContinueButton()
                    btnPlayNow.requestFocus()
                } else {
                    tvEpisodeHeader.text = "Chưa có tập phim khả dụng"
                    btnPlayNow.isEnabled = false
                }
            } catch (e: Exception) {
                Toast.makeText(this@DetailActivity, "Không tải được chi tiết phim: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildServerButtons() {
        serverContainer.removeAllViews()
        servers.forEachIndexed { idx, server ->
            val btn = Button(this).apply {
                text = server.serverName ?: "Server ${idx + 1}"
                isAllCaps = false
                setTextColor(ContextCompat.getColor(this@DetailActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_tab_selector)
                isFocusable = true
                setPadding(24, 8, 24, 8)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * resources.displayMetrics.density).toInt()
                )
                params.marginEnd = (10 * resources.displayMetrics.density).toInt()
                layoutParams = params
                setOnClickListener {
                    selectServer(idx)
                }
            }
            serverContainer.addView(btn)
        }
    }

    private fun selectServer(serverIdx: Int) {
        if (serverIdx !in servers.indices) return
        currentServerIndex = serverIdx
        for (i in 0 until serverContainer.childCount) {
            serverContainer.getChildAt(i).isSelected = (i == serverIdx)
        }
        currentEpisodes = getPlayableEpisodes(serverIdx)
        val savedServer = historyManager.getLastServerIndex(movieSlug)
        val savedEp = if (savedServer == serverIdx) historyManager.getLastEpisodeIndex(movieSlug) else -1
        tvEpisodeHeader.text = "Danh sách tập (${currentEpisodes.size} tập)"
        episodeAdapter.submitList(currentEpisodes, savedEp)
    }

    private fun getPlayableEpisodes(serverIdx: Int): List<EpisodeData> {
        if (serverIdx !in servers.indices) return emptyList()
        return servers[serverIdx].serverData?.filter { !it.linkM3u8.isNullOrBlank() } ?: emptyList()
    }

    private fun updateContinueButton() {
        val lastEpIdx = historyManager.getLastEpisodeIndex(movieSlug)
        val lastEpName = historyManager.getLastEpisodeName(movieSlug)
        btnPlayNow.text = if (lastEpIdx >= 0 && lastEpName.isNotBlank()) {
            "Tiếp tục xem ($lastEpName)"
        } else {
            "Xem ngay"
        }
    }

    private fun openPlayer(serverIdx: Int, epIndex: Int) {
        val eps = getPlayableEpisodes(serverIdx)
        if (eps.isEmpty() || epIndex !in eps.indices) {
            Toast.makeText(this, "Tập này chưa có link phát trực tiếp (.m3u8)", Toast.LENGTH_SHORT).show()
            return
        }
        val names = ArrayList<String>(eps.size)
        val urls = ArrayList<String>(eps.size)
        eps.forEach {
            names.add(it.name ?: "")
            urls.add(it.linkM3u8 ?: "")
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("slug", movieSlug)
            putExtra("title", movieTitle)
            putExtra("serverIndex", serverIdx)
            putExtra("episodeIndex", epIndex)
            putStringArrayListExtra("episodeNames", names)
            putStringArrayListExtra("episodeUrls", urls)
        }
        startActivity(intent)
    }
}
