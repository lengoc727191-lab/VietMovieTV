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
import com.vietmovietv.app.data.MovieDetail
import com.vietmovietv.app.data.MultiSourceRepository
import com.vietmovietv.app.data.WatchHistoryManager
import com.vietmovietv.app.ui.EpisodeAdapter
import com.vietmovietv.app.ui.FocusEffects
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
    private var preferredSource: String = ""
    private var currentDetail: MovieDetail? = null
    private var servers: List<EpisodeServer> = emptyList()
    private var currentServerIndex = 0
    private var currentEpisodes: List<EpisodeData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        movieSlug = intent.getStringExtra("slug") ?: ""
        movieTitle = intent.getStringExtra("title") ?: ""
        preferredSource = intent.getStringExtra("source") ?: ""
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
        FocusEffects.attachPopEffect(btnPlayNow, scaleUp = 1.08f, elevationPx = 16f)

        episodeAdapter = EpisodeAdapter { epIndex ->
            openPlayer(currentServerIndex, epIndex)
        }
        rvEpisodes.layoutManager = GridLayoutManager(this, 8)
        rvEpisodes.adapter = episodeAdapter

        btnPlayNow.setOnClickListener {
            val savedEp = historyManager.getLastEpisodeIndex(movieSlug)
            val savedServer = historyManager.getLastServerIndex(movieSlug)
            val targetServer = if (savedServer in servers.indices) savedServer else currentServerIndex
            val eps = servers.getOrNull(targetServer)?.episodes ?: emptyList()
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
                val agg = MultiSourceRepository.getAggregatedDetail(slug, preferredSource)
                val movie = agg.movie
                currentDetail = movie
                if (movie != null) {
                    movieTitle = movie.name.ifBlank { movieTitle }
                    tvDetailTitle.text = movieTitle

                    val metaParts = listOf(
                        movie.originName,
                        movie.year,
                        movie.quality,
                        movie.lang,
                        movie.episodeCurrent
                    ).filter { it.isNotBlank() }
                    tvDetailMeta.text = metaParts.joinToString("  •  ")

                    val cleanDesc = HtmlCompat.fromHtml(
                        movie.content,
                        HtmlCompat.FROM_HTML_MODE_LEGACY
                    ).toString().trim()
                    tvDetailDesc.text = cleanDesc.ifBlank { "Chưa có mô tả cho phim này." }

                    ivDetailPoster.load(movie.posterUrl.ifBlank { movie.thumbUrl }) { crossfade(true) }
                    ivBackdrop.load(movie.thumbUrl.ifBlank { movie.posterUrl }) { crossfade(400) }
                }

                servers = agg.servers

                if (servers.isNotEmpty()) {
                    val hasSaved = historyManager.getLastEpisodeIndex(movieSlug) >= 0
                    val savedServer = historyManager.getLastServerIndex(movieSlug)
                    currentServerIndex = if (hasSaved && savedServer in servers.indices) savedServer else 0
                    buildServerButtons()
                    selectServer(currentServerIndex)
                    updateContinueButton()
                    btnPlayNow.requestFocus()
                } else {
                    tvEpisodeHeader.text = "Không tìm thấy luồng phát trực tiếp (.m3u8) từ các nguồn"
                    btnPlayNow.isEnabled = false
                }
            } catch (e: Exception) {
                Toast.makeText(this@DetailActivity, "Lỗi tải chi tiết phim: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildServerButtons() {
        serverContainer.removeAllViews()
        servers.forEachIndexed { idx, server ->
            val isDub = server.isVietnameseDubbed()
            val btn = Button(this).apply {
                text = if (isDub) "★ ${server.serverName}" else server.serverName
                isAllCaps = false
                textSize = 12.5f
                setTextColor(
                    ContextCompat.getColor(
                        this@DetailActivity,
                        if (isDub) R.color.accent_green else R.color.text_primary
                    )
                )
                setBackgroundResource(R.drawable.bg_tab_selector)
                isFocusable = true
                setPadding(24, 6, 24, 6)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (38 * resources.displayMetrics.density).toInt()
                )
                params.marginEnd = (8 * resources.displayMetrics.density).toInt()
                layoutParams = params
                setOnClickListener {
                    selectServer(idx)
                }
            }
            FocusEffects.attachPopEffect(btn, scaleUp = 1.08f, elevationPx = 14f)
            serverContainer.addView(btn)
        }
    }

    private fun selectServer(serverIdx: Int) {
        if (serverIdx !in servers.indices) return
        currentServerIndex = serverIdx
        for (i in 0 until serverContainer.childCount) {
            serverContainer.getChildAt(i).isSelected = (i == serverIdx)
        }
        val srv = servers[serverIdx]
        currentEpisodes = srv.episodes
        val savedServer = historyManager.getLastServerIndex(movieSlug)
        val savedEp = if (savedServer == serverIdx) historyManager.getLastEpisodeIndex(movieSlug) else -1
        val dubHint = if (srv.isVietnameseDubbed()) " • Đã ưu tiên Thuyết Minh thật" else " • Có thể bật Thuyết minh AI khi xem"
        tvEpisodeHeader.text = "${srv.serverName}  •  ${currentEpisodes.size} tập$dubHint"
        episodeAdapter.submitList(currentEpisodes, savedEp)
    }

    private fun updateContinueButton() {
        val lastEpIdx = historyManager.getLastEpisodeIndex(movieSlug)
        val lastEpName = historyManager.getLastEpisodeName(movieSlug)
        btnPlayNow.text = if (lastEpIdx >= 0 && lastEpName.isNotBlank()) {
            "▶ Tiếp tục ($lastEpName)"
        } else {
            "▶ Xem ngay"
        }
    }

    private fun openPlayer(serverIdx: Int, epIndex: Int) {
        val srv = servers.getOrNull(serverIdx)
        val eps = srv?.episodes ?: emptyList()
        if (eps.isEmpty() || epIndex !in eps.indices) {
            Toast.makeText(this, "Tập này chưa có link phát (.m3u8)", Toast.LENGTH_SHORT).show()
            return
        }
        val names = ArrayList<String>(eps.size)
        val urls = ArrayList<String>(eps.size)
        eps.forEach {
            names.add(it.name)
            urls.add(it.linkM3u8)
        }

        val detail = currentDetail
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("slug", movieSlug)
            putExtra("title", movieTitle)
            putExtra("originName", detail?.originName ?: "")
            putExtra("imdbId", detail?.imdbId ?: "")
            putExtra("tmdbId", detail?.tmdbId ?: "")
            putExtra("movieType", detail?.movieType ?: if (eps.size > 1) "tv" else "movie")
            putExtra("season", detail?.season ?: 1)
            putExtra("serverName", srv?.serverName ?: "")
            putExtra("serverIndex", serverIdx)
            putExtra("episodeIndex", epIndex)
            putStringArrayListExtra("episodeNames", names)
            putStringArrayListExtra("episodeUrls", urls)
        }
        startActivity(intent)
    }
}
