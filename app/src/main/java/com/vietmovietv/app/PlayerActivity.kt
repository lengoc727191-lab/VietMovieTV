package com.vietmovietv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.vietmovietv.app.data.WatchHistoryManager

@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var tvPlayingTitle: TextView
    private lateinit var btnPrevEp: Button
    private lateinit var btnNextEp: Button
    private lateinit var tvSeekOsd: TextView

    private lateinit var historyManager: WatchHistoryManager
    private var player: ExoPlayer? = null

    private var movieSlug: String = ""
    private var movieTitle: String = ""
    private var serverIndex: Int = 0
    private var currentEpisodeIndex: Int = 0
    private var episodeNames: ArrayList<String> = arrayListOf()
    private var episodeUrls: ArrayList<String> = arrayListOf()

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { tvSeekOsd.visibility = View.GONE }

    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            handler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        enterFullscreen()

        historyManager = WatchHistoryManager(this)

        movieSlug = intent.getStringExtra("slug") ?: ""
        movieTitle = intent.getStringExtra("title") ?: ""
        serverIndex = intent.getIntExtra("serverIndex", 0)
        currentEpisodeIndex = intent.getIntExtra("episodeIndex", 0)
        episodeNames = intent.getStringArrayListExtra("episodeNames") ?: arrayListOf()
        episodeUrls = intent.getStringArrayListExtra("episodeUrls") ?: arrayListOf()

        playerView = findViewById(R.id.playerView)
        topOverlayBar = findViewById(R.id.topOverlayBar)
        tvPlayingTitle = findViewById(R.id.tvPlayingTitle)
        btnPrevEp = findViewById(R.id.btnPrevEp)
        btnNextEp = findViewById(R.id.btnNextEp)
        tvSeekOsd = findViewById(R.id.tvSeekOsd)

        listOf(btnPrevEp, btnNextEp).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                val b = v as Button
                b.setTextColor(
                    if (hasFocus) 0xFF000000.toInt()
                    else ContextCompat.getColor(this, R.color.text_primary)
                )
            }
        }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topOverlayBar.visibility = visibility
            }
        )

        btnPrevEp.setOnClickListener {
            if (currentEpisodeIndex > 0) {
                saveCurrentProgress()
                playEpisode(currentEpisodeIndex - 1)
            }
        }

        btnNextEp.setOnClickListener {
            if (currentEpisodeIndex < episodeUrls.size - 1) {
                saveCurrentProgress()
                playEpisode(currentEpisodeIndex + 1)
            }
        }

        initPlayer()
        playEpisode(currentEpisodeIndex)
    }

    private fun initPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (currentEpisodeIndex < episodeUrls.size - 1) {
                        Toast.makeText(this@PlayerActivity, "Chuyển sang tập tiếp theo...", Toast.LENGTH_SHORT).show()
                        playEpisode(currentEpisodeIndex + 1)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Lỗi phát video (${error.errorCodeName}). Bấm Back và chọn Server khác!",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun playEpisode(index: Int) {
        if (index !in episodeUrls.indices) return
        currentEpisodeIndex = index

        val epName = episodeNames.getOrElse(index) { "Tập ${index + 1}" }
        val url = episodeUrls[index]

        tvPlayingTitle.text = "$movieTitle  •  $epName"
        btnPrevEp.visibility = if (index > 0) View.VISIBLE else View.GONE
        btnNextEp.visibility = if (index < episodeUrls.size - 1) View.VISIBLE else View.GONE

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.fromUri(url)
        val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(mediaItem)

        val exo = player ?: return
        exo.setMediaSource(hlsSource)
        exo.prepare()

        val savedPos = historyManager.getPosition(movieSlug, serverIndex, index)
        if (savedPos > 5000L) {
            exo.seekTo(savedPos)
            showSeekOsd("Tiếp tục phát...")
        }
        exo.playWhenReady = true
    }

    private fun showSeekOsd(text: String) {
        tvSeekOsd.text = text
        tvSeekOsd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 1200L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val exo = player
            val controllerVisible = playerView.isControllerFullyVisible

            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (controllerVisible) {
                        playerView.hideController()
                        return true
                    }
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controllerVisible && exo != null) {
                        exo.seekTo(exo.currentPosition + 10_000L)
                        showSeekOsd("+10s")
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controllerVisible && exo != null) {
                        exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                        showSeekOsd("-10s")
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controllerVisible && exo != null) {
                        if (exo.isPlaying) {
                            exo.pause()
                            showSeekOsd("Tạm dừng")
                        } else {
                            exo.play()
                            showSeekOsd("Tiếp tục")
                        }
                        playerView.showController()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!controllerVisible) {
                        playerView.showController()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveCurrentProgress() {
        val exo = player ?: return
        val pos = exo.currentPosition
        val duration = exo.duration
        val epName = episodeNames.getOrElse(currentEpisodeIndex) { "Tập ${currentEpisodeIndex + 1}" }
        val savePos = if (duration > 30_000L && pos > duration - 15_000L) 0L else pos
        historyManager.saveProgress(movieSlug, serverIndex, currentEpisodeIndex, epName, savePos)
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        handler.postDelayed(saveProgressRunnable, 5000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveProgressRunnable)
        saveCurrentProgress()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }
}
