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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.vietmovietv.app.data.WatchHistoryManager
import com.vietmovietv.app.tts.AiDubbingEngine
import com.vietmovietv.app.tts.DubSettingsManager
import com.vietmovietv.app.tts.SubtitleCue
import com.vietmovietv.app.tts.SubtitleRepository
import com.vietmovietv.app.ui.FocusEffects
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var tvPlayingTitle: TextView
    private lateinit var btnPrevEp: Button
    private lateinit var btnNextEp: Button
    private lateinit var btnAiDubToggle: Button
    private lateinit var btnAiVoice: Button
    private lateinit var btnSyncMinus: Button
    private lateinit var btnSyncPlus: Button
    private lateinit var tvAiStatus: TextView
    private lateinit var tvSeekOsd: TextView

    private lateinit var historyManager: WatchHistoryManager
    private lateinit var dubSettings: DubSettingsManager
    private lateinit var aiDubEngine: AiDubbingEngine
    private var player: ExoPlayer? = null

    private var movieSlug: String = ""
    private var movieTitle: String = ""
    private var originName: String = ""
    private var imdbId: String = ""
    private var tmdbId: String = ""
    private var movieType: String = "movie"
    private var season: Int = 1
    private var serverName: String = ""
    private var serverIndex: Int = 0
    private var currentEpisodeIndex: Int = 0
    private var episodeNames: ArrayList<String> = arrayListOf()
    private var episodeUrls: ArrayList<String> = arrayListOf()

    private var aiDubEnabled = false
    private var syncOffsetMs = 0L
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var lastSpokenCueIndex = -1
    private var lastLiveCueText = ""
    private var subtitleLoadJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { tvSeekOsd.visibility = View.GONE }

    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            handler.postDelayed(this, 5000L)
        }
    }

    private val aiDubTickRunnable = object : Runnable {
        override fun run() {
            if (aiDubEnabled) {
                checkSubtitleSync()
            }
            handler.postDelayed(this, 180L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        enterFullscreen()

        historyManager = WatchHistoryManager(this)
        dubSettings = DubSettingsManager(this)
        aiDubEngine = AiDubbingEngine(this, dubSettings) { vol ->
            player?.volume = vol
        }

        movieSlug = intent.getStringExtra("slug") ?: ""
        movieTitle = intent.getStringExtra("title") ?: ""
        originName = intent.getStringExtra("originName") ?: ""
        imdbId = intent.getStringExtra("imdbId") ?: ""
        tmdbId = intent.getStringExtra("tmdbId") ?: ""
        movieType = intent.getStringExtra("movieType") ?: "movie"
        season = intent.getIntExtra("season", 1)
        serverName = intent.getStringExtra("serverName") ?: ""
        serverIndex = intent.getIntExtra("serverIndex", 0)
        currentEpisodeIndex = intent.getIntExtra("episodeIndex", 0)
        episodeNames = intent.getStringArrayListExtra("episodeNames") ?: arrayListOf()
        episodeUrls = intent.getStringArrayListExtra("episodeUrls") ?: arrayListOf()

        playerView = findViewById(R.id.playerView)
        topOverlayBar = findViewById(R.id.topOverlayBar)
        tvPlayingTitle = findViewById(R.id.tvPlayingTitle)
        btnPrevEp = findViewById(R.id.btnPrevEp)
        btnNextEp = findViewById(R.id.btnNextEp)
        btnAiDubToggle = findViewById(R.id.btnAiDubToggle)
        btnAiVoice = findViewById(R.id.btnAiVoice)
        btnSyncMinus = findViewById(R.id.btnSyncMinus)
        btnSyncPlus = findViewById(R.id.btnSyncPlus)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvSeekOsd = findViewById(R.id.tvSeekOsd)

        listOf(btnPrevEp, btnNextEp, btnAiDubToggle, btnAiVoice, btnSyncMinus, btnSyncPlus)
            .forEach { FocusEffects.attachPopEffect(it, scaleUp = 1.08f, elevationPx = 12f) }

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

        btnAiDubToggle.setOnClickListener {
            toggleAiDubbing()
        }

        btnAiVoice.setOnClickListener {
            showVoicePickerDialog()
        }

        btnSyncMinus.setOnClickListener {
            syncOffsetMs -= 500L
            showSyncOsd()
        }

        btnSyncPlus.setOnClickListener {
            syncOffsetMs += 500L
            showSyncOsd()
        }

        updateVoiceButtonLabel()
        updateAiButtons()

        initPlayer()
        playEpisode(currentEpisodeIndex)
    }

    private fun updateVoiceButtonLabel() {
        btnAiVoice.text = "Giọng: ${aiDubEngine.getCurrentVoice().label} ▾"
    }

    private fun showVoicePickerDialog() {
        val allVoices = aiDubEngine.getAllVoices()
        val items = allVoices.map { "${it.label}\n   ${it.description}" }.toTypedArray()
        val currentIdx = aiDubEngine.getCurrentVoiceIndex()

        AlertDialog.Builder(this)
            .setTitle("Chọn giọng Thuyết Minh AI (${allVoices.size} giọng)")
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val chosen = aiDubEngine.selectVoice(which)
                updateVoiceButtonLabel()
                showSeekOsd("Đã chọn: ${chosen.label}")
                dialog.dismiss()
                if (!aiDubEnabled) {
                    aiDubEngine.previewCurrentVoice()
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun initPlayer() {
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    aiDubEngine.stopCurrentSpeech()
                    if (currentEpisodeIndex < episodeUrls.size - 1) {
                        Toast.makeText(this@PlayerActivity, "Chuyển sang tập tiếp theo...", Toast.LENGTH_SHORT).show()
                        playEpisode(currentEpisodeIndex + 1)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    aiDubEngine.stopCurrentSpeech()
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                if (!aiDubEnabled || subtitleCues.isNotEmpty()) return
                val raw = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString(" ")
                val clean = SubtitleRepository.cleanSpokenText(raw)
                if (clean.length >= 2 && clean != lastLiveCueText) {
                    lastLiveCueText = clean
                    val liveCue = SubtitleCue(
                        startMs = 0L,
                        endMs = 3000L,
                        text = clean,
                        spokenText = clean,
                        gender = SubtitleRepository.guessGenderHeuristic(clean),
                        emotion = SubtitleRepository.detectEmotionHeuristic(clean)
                    )
                    aiDubEngine.speakCue(liveCue, 3000L)
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
        aiDubEngine.stopCurrentSpeech()
        subtitleLoadJob?.cancel()
        subtitleCues = emptyList()
        lastSpokenCueIndex = -1
        lastLiveCueText = ""

        val epName = episodeNames.getOrElse(index) { "Tập ${index + 1}" }
        val url = episodeUrls[index]

        tvPlayingTitle.text = "$movieTitle - $epName"
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
        exo.volume = 1.0f
        exo.setMediaSource(hlsSource)
        exo.prepare()

        val savedPos = historyManager.getPosition(movieSlug, serverIndex, index)
        if (savedPos > 5000L) {
            exo.seekTo(savedPos)
            showSeekOsd("Tiếp tục phát...")
        }
        exo.playWhenReady = true

        if (aiDubEnabled) {
            loadSubtitlesForCurrentEpisode()
        } else {
            val lowerSrv = serverName.lowercase()
            tvAiStatus.text = if (lowerSrv.contains("thuyết minh") || lowerSrv.contains("lồng tiếng")) {
                "Đang phát bản Thuyết Minh / Lồng Tiếng thật"
            } else {
                "Bấm 'Thuyết minh AI' hoặc 'Giọng' để chọn giọng đọc"
            }
        }
    }

    private fun toggleAiDubbing() {
        aiDubEnabled = !aiDubEnabled
        updateAiButtons()
        if (aiDubEnabled) {
            showSeekOsd("Đã bật Thuyết minh AI")
            if (subtitleCues.isEmpty()) {
                loadSubtitlesForCurrentEpisode()
            } else {
                tvAiStatus.text = "Đang bật Thuyết minh AI (${subtitleCues.size} câu)"
            }
        } else {
            subtitleLoadJob?.cancel()
            aiDubEngine.stopCurrentSpeech(restoreImmediately = true)
            tvAiStatus.text = "Đã tắt Thuyết minh AI"
            showSeekOsd("Đã tắt Thuyết minh AI")
        }
    }

    private fun updateAiButtons() {
        btnAiDubToggle.text = if (aiDubEnabled) "Thuyết minh AI: BẬT" else "Thuyết minh AI: Tắt"
        btnAiDubToggle.isSelected = aiDubEnabled
    }

    private fun extractEpisodeNumber(): Int {
        val name = episodeNames.getOrElse(currentEpisodeIndex) { "" }
        val match = Regex("""\d+""").find(name)
        return match?.value?.toIntOrNull() ?: (currentEpisodeIndex + 1)
    }

    private fun loadSubtitlesForCurrentEpisode() {
        subtitleLoadJob?.cancel()
        tvAiStatus.text = "Đang tải & tối ưu câu thoại Tiếng Việt..."
        val epNum = extractEpisodeNumber()

        subtitleLoadJob = lifecycleScope.launch {
            val result = SubtitleRepository.findVietnameseSubtitles(
                imdbIdInput = imdbId,
                tmdbIdInput = tmdbId,
                originName = originName,
                movieTitle = movieTitle,
                movieType = movieType,
                season = season,
                episodeNumber = epNum
            )
            subtitleCues = result.cues
            lastSpokenCueIndex = -1

            if (subtitleCues.isNotEmpty()) {
                tvAiStatus.text = "AI sẵn sàng • ${result.sourceInfo}"
                showSeekOsd("Đã tải ${subtitleCues.size} câu thoại!")
            } else {
                tvAiStatus.text = "${result.sourceInfo} (chờ phụ đề luồng nếu có)"
                Toast.makeText(
                    this@PlayerActivity,
                    "Tập này chưa có phụ đề rời (.srt) trên kho mở.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkSubtitleSync() {
        val exo = player ?: return
        if (!exo.isPlaying || subtitleCues.isEmpty()) return

        val pos = exo.currentPosition + syncOffsetMs
        val idx = findCueIndex(pos)
        if (idx >= 0 && idx != lastSpokenCueIndex) {
            val cue = subtitleCues[idx]
            if (pos - cue.startMs <= 2600L) {
                lastSpokenCueIndex = idx
                aiDubEngine.speakCue(cue, computeAvailableWindowMs(idx))
                for (offset in 1..3) {
                    val nextIdx = idx + offset
                    if (nextIdx < subtitleCues.size) {
                        aiDubEngine.preloadCue(subtitleCues[nextIdx], computeAvailableWindowMs(nextIdx))
                    }
                }
            }
        }
    }

    private fun computeAvailableWindowMs(idx: Int): Long {
        val cue = subtitleCues[idx]
        val cueDur = (cue.endMs - cue.startMs).coerceAtLeast(1000L)
        if (idx + 1 < subtitleCues.size) {
            val untilNext = subtitleCues[idx + 1].startMs - cue.startMs
            return maxOf(cueDur, untilNext.coerceAtMost(cueDur + 1200L))
        }
        return cueDur + 800L
    }

    private fun findCueIndex(posMs: Long): Int {
        var low = 0
        var high = subtitleCues.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = subtitleCues[mid]
            when {
                posMs < cue.startMs -> high = mid - 1
                posMs > cue.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun showSyncOsd() {
        val sec = syncOffsetMs / 1000.0
        val sign = if (sec >= 0) "+" else ""
        val formatted = String.format(Locale.US, "Đồng bộ: %s%.1fs", sign, sec)
        tvAiStatus.text = formatted
        showSeekOsd(formatted)
    }

    private fun showSeekOsd(text: String) {
        tvSeekOsd.text = text
        tvSeekOsd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 1400L)
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
                        lastSpokenCueIndex = -1
                        aiDubEngine.stopCurrentSpeech()
                        showSeekOsd("+10s")
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!controllerVisible && exo != null) {
                        exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                        lastSpokenCueIndex = -1
                        aiDubEngine.stopCurrentSpeech()
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
        handler.postDelayed(aiDubTickRunnable, 180L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveProgressRunnable)
        handler.removeCallbacks(aiDubTickRunnable)
        saveCurrentProgress()
        aiDubEngine.stopCurrentSpeech()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        subtitleLoadJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        aiDubEngine.release()
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
