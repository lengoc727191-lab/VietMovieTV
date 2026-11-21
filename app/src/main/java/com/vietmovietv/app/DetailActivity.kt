package com.vietmovietv.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var tvDetailTag: TextView
    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailMeta: TextView
    private lateinit var tvDetailDesc: TextView
    private lateinit var btnPlayNow: Button
    private lateinit var btnChooseServer: Button
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
        tvDetailTag = findViewById(R.id.tvDetailTag)
        tvDetailTitle = findViewById(R.id.tvDetailTitle)
        tvDetailMeta = findViewById(R.id.tvDetailMeta)
        tvDetailDesc = findViewById(R.id.tvDetailDesc)
        btnPlayNow = findViewById(R.id.btnPlayNow)
        btnChooseServer = findViewById(R.id.btnChooseServer)
        tvEpisodeHeader = findViewById(R.id.tvEpisodeHeader)
        rvEpisodes = findViewById(R.id.rvEpisodes)
        progressBar = findViewById(R.id.detailProgressBar)

        tvDetailTitle.text = movieTitle
        FocusEffects.attachPopEffect(btnPlayNow, scaleUp = 1.05f, elevationPx = 14f)

        btnChooseServer.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val b = v as Button
            if (hasFocus) {
                b.setTextColor(0xFF000000.toInt())
                v.elevation = 12f
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150L).start()
            } else {
                b.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                v.elevation = 0f
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120L).start()
            }
        }

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

        btnChooseServer.setOnClickListener {
            showServerPickerDialog()
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
                        movie.year,
                        movie.quality,
                        movie.lang,
                        movie.episodeCurrent,
                        movie.time
                    ).filter { it.isNotBlank() }
                    tvDetailMeta.text = metaParts.joinToString("   •   ")

                    val cleanDesc = HtmlCompat.fromHtml(
                        movie.content,
                        HtmlCompat.FROM_HTML_MODE_LEGACY
                    ).toString().trim()
                    tvDetailDesc.text = cleanDesc.ifBlank { "Chưa có mô tả cho phim này." }

                    ivBackdrop.load(movie.thumbUrl.ifBlank { movie.posterUrl }) {
                        crossfade(400)
                        size(1280, 720)
                    }
                }

                servers = agg.servers

                if (servers.isNotEmpty()) {
                    val hasSaved = historyManager.getLastEpisodeIndex(movieSlug) >= 0
                    val savedServer = historyManager.getLastServerIndex(movieSlug)
                    currentServerIndex = if (hasSaved && savedServer in servers.indices) savedServer else 0
                    selectServer(currentServerIndex)
                    updateContinueButton()
                    btnPlayNow.requestFocus()
                } else {
                    btnChooseServer.text = "Không có nguồn phát (.m3u8)"
                    btnChooseServer.isEnabled = false
                    btnPlayNow.isEnabled = false
                }
            } catch (e: Exception) {
                Toast.makeText(this@DetailActivity, "Lỗi tải chi tiết phim: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showServerPickerDialog() {
        if (servers.isEmpty()) return
        val items = servers.map { srv ->
            val prefix = if (srv.isVietnameseDubbed()) "★ " else ""
            "$prefix${srv.serverName} (${srv.episodes.size} tập)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chọn Server phát (${servers.size} nguồn)")
            .setSingleChoiceItems(items, currentServerIndex) { dialog, which ->
                selectServer(which)
                dialog.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun selectServer(serverIdx: Int) {
        if (serverIdx !in servers.indices) return
        currentServerIndex = serverIdx
        val srv = servers[serverIdx]
        currentEpisodes = srv.episodes
        val isDub = srv.isVietnameseDubbed()

        tvDetailTag.text = if (isDub) "VIETMOVIE  •  THUYẾT MINH THẬT" else "VIETMOVIE  •  VIETSUB"
        btnChooseServer.text = "Server: ${srv.serverName} ▾"

        val savedServer = historyManager.getLastServerIndex(movieSlug)
        val savedEp = if (savedServer == serverIdx) historyManager.getLastEpisodeIndex(movieSlug) else -1
        tvEpisodeHeader.text = "Danh sách tập (${currentEpisodes.size} tập)  •  ${servers.size} server"
        episodeAdapter.submitList(currentEpisodes, savedEp)
    }

    private fun updateContinueButton() {
        val lastEpIdx = historyManager.getLastEpisodeIndex(movieSlug)
        val lastEpName = historyManager.getLastEpisodeName(movieSlug)
        btnPlayNow.text = if (lastEpIdx >= 0 && lastEpName.isNotBlank()) {
            "▶   Tiếp tục ($lastEpName)"
        } else {
            "▶   Xem ngay"
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
        val epName = eps[epIndex].name.ifBlank { "Tập ${epIndex + 1}" }
        // Record to "Đang xem dở" Home Swimlane immediately!
        historyManager.recordWatchedMovie(
            slug = movieSlug,
            name = movieTitle,
            originName = detail?.originName ?: "",
            posterUrl = detail?.posterUrl ?: "",
            thumbUrl = detail?.thumbUrl ?: "",
            year = detail?.year ?: "",
            episodeName = epName,
            lang = detail?.lang ?: "",
            sourceLabel = preferredSource
        )

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
