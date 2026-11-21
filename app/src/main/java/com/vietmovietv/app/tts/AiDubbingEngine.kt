package com.vietmovietv.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TtsBackend {
    EDGE_NEURAL,
    GOOGLE_SYSTEM,
    GOOGLE_TRANSLATE_WEB
}

data class VoiceProfile(
    val id: String,
    val label: String,
    val description: String,
    val backend: TtsBackend,
    val edgeVoiceName: String = "vi-VN-HoaiMyNeural",
    val basePitchHz: Int = -5,
    val baseRatePct: Int = 3,
    val dualGenderAuto: Boolean = false,
    val systemVoiceName: String? = null,
    val systemPitch: Float = 0.96f,
    val systemRate: Float = 1.06f
)

class AiDubbingEngine(
    private val context: Context,
    private val settings: DubSettingsManager,
    private val onSetMovieVolume: (Float) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheDir = File(context.cacheDir, "ai_dub_v24").apply { mkdirs() }
    private val preloadedFiles = ConcurrentHashMap<String, File>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentSpeakJob: Job? = null
    private var isSpeaking = false
    private var edgeFailStreak = 0
    private var alternatingMaleTurn = false

    private val duckVolume = 0.22f
    private var currentMovieVolume = 1.0f
    private var targetMovieVolume = 1.0f
    private var volumeAnimRunnable: Runnable? = null

    private val holdReleaseRunnable = Runnable {
        if (!isSpeaking) {
            animateMovieVolumeTo(1.0f, 650L)
        }
    }

    private val voices = mutableListOf(
        VoiceProfile(
            id = "pro_nu_am",
            label = "Nữ Thuyết Minh Ấm (Edge Pro)",
            description = "Giọng nữ trầm ấm, lấy hơi tự nhiên — khuyên dùng",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            basePitchHz = -5,
            baseRatePct = 3,
            dualGenderAuto = false
        ),
        VoiceProfile(
            id = "pro_doi_thoai",
            label = "Nam - Nữ Đối Thoại Tự Động (Edge Pro)",
            description = "Tự đổi giọng Nam / Nữ theo từng lượt thoại",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            basePitchHz = -3,
            baseRatePct = 4,
            dualGenderAuto = true
        ),
        VoiceProfile(
            id = "pro_nam_tram",
            label = "Nam Trầm Điện Ảnh (Edge Pro)",
            description = "Giọng nam trầm sâu, hợp phim hành động & cổ trang",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-NamMinhNeural",
            basePitchHz = -6,
            baseRatePct = 2,
            dualGenderAuto = false
        ),
        VoiceProfile(
            id = "pro_nu_nhe",
            label = "Nữ Truyền Cảm Nhẹ Nhàng (Edge)",
            description = "Nhịp chậm rãi, hợp phim tâm lý & tình cảm",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            basePitchHz = -2,
            baseRatePct = -2,
            dualGenderAuto = false
        ),
        VoiceProfile(
            id = "edge_hoaimy_std",
            label = "Hoài My Chuẩn (Edge Neural)",
            description = "Giọng nữ sáng rõ nguyên bản Microsoft",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            basePitchHz = 0,
            baseRatePct = 8,
            dualGenderAuto = false
        ),
        VoiceProfile(
            id = "edge_namminh_std",
            label = "Nam Minh Chuẩn (Edge Neural)",
            description = "Giọng nam dứt khoát nguyên bản Microsoft",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-NamMinhNeural",
            basePitchHz = 0,
            baseRatePct = 8,
            dualGenderAuto = false
        ),
        VoiceProfile(
            id = "google_web_vi",
            label = "Google Nữ Trực Tuyến (Online)",
            description = "Ổn định cao, chạy tốt kể cả khi mạng yếu",
            backend = TtsBackend.GOOGLE_TRANSLATE_WEB
        ),
        VoiceProfile(
            id = "google_sys_default",
            label = "Google TTS Hệ Thống TV",
            description = "Đọc trực tiếp từ bộ máy TTS cài sẵn trên TV",
            backend = TtsBackend.GOOGLE_SYSTEM
        )
    )

    private var currentVoiceIndex = settings.getSavedVoiceIndex().coerceIn(0, voices.size - 1)

    init {
        initAndroidTts()
    }

    private fun initAndroidTts() {
        try {
            androidTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = androidTts ?: return@TextToSpeech
                    val res = tts.setLanguage(Locale("vi", "VN"))
                    androidTtsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)

                    if (androidTtsReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            val viVoices = tts.voices
                                ?.filter { it.locale?.language.equals("vi", ignoreCase = true) }
                                ?.sortedBy { it.name }
                                ?.take(4)
                                ?: emptyList()
                            viVoices.forEachIndexed { idx, v ->
                                synchronized(voices) {
                                    voices.add(
                                        VoiceProfile(
                                            id = "sys_vi_$idx",
                                            label = "Google TV Giọng ${idx + 1} (${v.name.takeLast(6)})",
                                            description = "Giọng hệ thống tiếng Việt trên TV",
                                            backend = TtsBackend.GOOGLE_SYSTEM,
                                            systemVoiceName = v.name
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = markSpeakingStart()
                        override fun onDone(utteranceId: String?) = markSpeakingEnd()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = markSpeakingEnd()
                    })
                }
            }
        } catch (_: Exception) {
            androidTtsReady = false
        }
    }

    fun getAllVoices(): List<VoiceProfile> = synchronized(voices) {
        voices.toList()
    }

    fun getCurrentVoiceIndex(): Int = synchronized(voices) {
        currentVoiceIndex.coerceIn(voices.indices)
    }

    fun getCurrentVoice(): VoiceProfile = synchronized(voices) {
        voices[currentVoiceIndex.coerceIn(voices.indices)]
    }

    fun selectVoice(index: Int): VoiceProfile = synchronized(voices) {
        currentVoiceIndex = index.coerceIn(voices.indices)
        settings.setSavedVoiceIndex(currentVoiceIndex)
        edgeFailStreak = 0
        clearPreloadCache()
        voices[currentVoiceIndex]
    }

    fun previewCurrentVoice() {
        val sample = SubtitleCue(
            startMs = 0L,
            endMs = 3500L,
            text = "Xin chào, đây là giọng thuyết minh phim bạn vừa chọn.",
            spokenText = "Xin chào, đây là giọng thuyết minh phim bạn vừa chọn.",
            emotion = "normal"
        )
        speakCue(sample, 3800L)
    }

    fun clearPreloadCache() {
        preloadedFiles.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private data class SpeechParams(
        val edgeVoice: String,
        val pitchHz: Int,
        val ratePct: Int,
        val cacheKey: String
    )

    private fun computeSpeechParams(cue: SubtitleCue, availableMs: Long, voice: VoiceProfile): SpeechParams {
        val text = cue.spokenText.ifBlank { cue.text }

        val chosenEdgeVoice = if (voice.dualGenderAuto) {
            when (cue.gender) {
                "M" -> "vi-VN-NamMinhNeural"
                "F" -> "vi-VN-HoaiMyNeural"
                else -> {
                    if (cue.isDialogueSwitch) alternatingMaleTurn = !alternatingMaleTurn
                    if (alternatingMaleTurn) "vi-VN-NamMinhNeural" else "vi-VN-HoaiMyNeural"
                }
            }
        } else {
            voice.edgeVoiceName
        }

        val sec = (availableMs.coerceIn(1100L, 9000L)) / 1000.0
        val naturalCharsPerSec = 13.0
        val neededMultiplier = (text.length / sec) / naturalCharsPerSec
        val dynamicRate = when {
            neededMultiplier <= 0.85 -> voice.baseRatePct - 3
            neededMultiplier <= 1.05 -> voice.baseRatePct + 1
            else -> ((neededMultiplier - 1.0) * 62.0).roundToInt() + voice.baseRatePct
        }

        val emotionPitchDelta = when (cue.emotion) {
            "urgent", "excited" -> 4
            "sad", "whisper" -> -4
            "question" -> 2
            else -> 0
        }
        val emotionRateDelta = when (cue.emotion) {
            "urgent", "excited" -> 5
            "sad", "whisper" -> -5
            else -> 0
        }

        val finalPitch = (voice.basePitchHz + emotionPitchDelta).coerceIn(-12, 10)
        val finalRate = (dynamicRate + emotionRateDelta).coerceIn(-10, 32)
        val rateBucket = (finalRate / 4) * 4
        val key = "${voice.id}_${chosenEdgeVoice}_${finalPitch}_${rateBucket}_${text.hashCode()}"
        return SpeechParams(chosenEdgeVoice, finalPitch, rateBucket, key)
    }

    fun preloadCue(cue: SubtitleCue, availableMs: Long) {
        val voice = getCurrentVoice()
        if (voice.backend == TtsBackend.GOOGLE_SYSTEM) return
        val text = cue.spokenText.ifBlank { cue.text }.trim()
        if (text.length < 2) return

        val params = computeSpeechParams(cue, availableMs, voice)
        if (preloadedFiles.containsKey(params.cacheKey)) return

        scope.launch {
            val file = when (voice.backend) {
                TtsBackend.EDGE_NEURAL -> synthesizeEdgeMp3(text, params) ?: synthesizeGoogleWebMp3(text)
                TtsBackend.GOOGLE_TRANSLATE_WEB -> synthesizeGoogleWebMp3(text)
                TtsBackend.GOOGLE_SYSTEM -> null
            }
            if (file != null && file.length() > 100L) {
                preloadedFiles[params.cacheKey] = file
            }
        }
    }

    fun speakCue(cue: SubtitleCue, availableMs: Long) {
        val text = cue.spokenText.ifBlank { cue.text }.trim()
        if (text.length < 2) return

        currentSpeakJob?.cancel()
        val voice = getCurrentVoice()
        val params = computeSpeechParams(cue, availableMs, voice)

        currentSpeakJob = scope.launch {
            val fileToPlay: File? = when (voice.backend) {
                TtsBackend.GOOGLE_SYSTEM -> null
                TtsBackend.GOOGLE_TRANSLATE_WEB -> {
                    preloadedFiles.remove(params.cacheKey) ?: synthesizeGoogleWebMp3(text)
                }
                TtsBackend.EDGE_NEURAL -> {
                    preloadedFiles.remove(params.cacheKey) ?: if (edgeFailStreak < 3) {
                        val edgeFile = synthesizeEdgeMp3(text, params)
                        if (edgeFile != null) {
                            edgeFailStreak = 0
                            edgeFile
                        } else {
                            edgeFailStreak++
                            synthesizeGoogleWebMp3(text)
                        }
                    } else {
                        synthesizeGoogleWebMp3(text)
                    }
                }
            }

            // Let previous sentence finish gracefully if within 450ms of ending
            val remainingMs = getRemainingPlaybackMs()
            if (remainingMs in 40L..450L) {
                delay(remainingMs + 30L)
            }

            if (voice.backend == TtsBackend.GOOGLE_SYSTEM) {
                if (androidTtsReady) {
                    speakViaAndroidTts(text, voice, params.ratePct)
                } else {
                    val fallback = synthesizeGoogleWebMp3(text)
                    if (fallback != null) playAudioFile(fallback)
                }
            } else if (fileToPlay != null) {
                playAudioFile(fileToPlay)
            } else if (androidTtsReady) {
                speakViaAndroidTts(text, voice, params.ratePct)
            }
        }
    }

    private fun getRemainingPlaybackMs(): Long {
        val mp = mediaPlayer ?: return 0L
        return try {
            if (mp.isPlaying && mp.duration > 0) {
                (mp.duration - mp.currentPosition).toLong().coerceAtLeast(0L)
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun stopCurrentSpeech(restoreImmediately: Boolean = true) {
        currentSpeakJob?.cancel()
        mainHandler.post {
            try {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
                mediaPlayer = null
            } catch (_: Exception) {
            }
            try {
                androidTts?.stop()
            } catch (_: Exception) {
            }
            isSpeaking = false
            mainHandler.removeCallbacks(holdReleaseRunnable)
            if (restoreImmediately) {
                animateMovieVolumeTo(1.0f, 250L)
            }
        }
    }

    fun release() {
        stopCurrentSpeech(restoreImmediately = true)
        scope.cancel()
        clearPreloadCache()
        try {
            androidTts?.shutdown()
            androidTts = null
        } catch (_: Exception) {
        }
    }

    private fun markSpeakingStart() {
        mainHandler.removeCallbacks(holdReleaseRunnable)
        isSpeaking = true
        mainHandler.post {
            animateMovieVolumeTo(duckVolume, 200L)
        }
    }

    private fun markSpeakingEnd() {
        isSpeaking = false
        mainHandler.removeCallbacks(holdReleaseRunnable)
        mainHandler.postDelayed(holdReleaseRunnable, 1800L)
    }

    private fun animateMovieVolumeTo(target: Float, durationMs: Long) {
        if (abs(targetMovieVolume - target) < 0.01f && abs(currentMovieVolume - target) < 0.01f) {
            return
        }
        targetMovieVolume = target
        volumeAnimRunnable?.let { mainHandler.removeCallbacks(it) }

        val startVol = currentMovieVolume
        val steps = (durationMs / 25L).coerceAtLeast(1L).toInt()
        var currentStep = 0

        val r = object : Runnable {
            override fun run() {
                currentStep++
                val frac = (currentStep.toFloat() / steps.toFloat()).coerceIn(0f, 1f)
                val eased = 1f - (1f - frac) * (1f - frac)
                currentMovieVolume = startVol + (target - startVol) * eased
                onSetMovieVolume(currentMovieVolume)
                if (currentStep < steps) {
                    mainHandler.postDelayed(this, 25L)
                }
            }
        }
        volumeAnimRunnable = r
        mainHandler.post(r)
    }

    private fun speakViaAndroidTts(text: String, voice: VoiceProfile, ratePct: Int) {
        mainHandler.post {
            val tts = androidTts ?: return@post
            try {
                tts.setPitch(voice.systemPitch)
                val rateFloat = (1.0f + ratePct / 100f).coerceIn(0.85f, 1.42f)
                tts.setSpeechRate(rateFloat)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && voice.systemVoiceName != null) {
                    val matched: Voice? = tts.voices?.firstOrNull { it.name == voice.systemVoiceName }
                    if (matched != null) tts.voice = matched
                }
                markSpeakingStart()
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dub_${System.currentTimeMillis()}")
            } catch (_: Exception) {
                markSpeakingEnd()
            }
        }
    }

    private fun playAudioFile(file: File) {
        mainHandler.post {
            try {
                mediaPlayer?.let { old ->
                    if (old.isPlaying) old.stop()
                    old.release()
                }
                val mp = MediaPlayer()
                mediaPlayer = mp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { player ->
                    markSpeakingStart()
                    player.start()
                }
                mp.setOnCompletionListener { player ->
                    player.release()
                    if (mediaPlayer == player) mediaPlayer = null
                    file.delete()
                    markSpeakingEnd()
                }
                mp.setOnErrorListener { player, _, _ ->
                    player.release()
                    if (mediaPlayer == player) mediaPlayer = null
                    file.delete()
                    markSpeakingEnd()
                    true
                }
                mp.prepareAsync()
            } catch (_: Exception) {
                file.delete()
                markSpeakingEnd()
            }
        }
    }

    private fun generateSecMsGec(): String {
        val winEpoch = 11644473600L
        var ticks = (System.currentTimeMillis() / 1000L) + winEpoch
        ticks -= (ticks % 300L)
        ticks *= 10_000_000L
        val raw = "${ticks}6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun buildSsmlBodyWithPauses(rawText: String): String {
        val escaped = rawText
            .take(360)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        return escaped
            .replace("...", "<break time='250ms'/>")
            .replace("…", "<break time='250ms'/>")
            .replace(" — ", "<break time='180ms'/>")
            .replace(" - ", "<break time='150ms'/>")
            .replace("; ", ", <break time='160ms'/>")
    }

    private suspend fun synthesizeEdgeMp3(text: String, params: SpeechParams): File? = withContext(Dispatchers.IO) {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val gec = generateSecMsGec()
        val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=1-130.0.2849.68" +
            "&ConnectionId=$connId"

        val deferred = CompletableDeferred<ByteArray?>()
        val audioOut = ByteArrayOutputStream()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
            )
            .build()

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val dateStr = sdf.format(Date())

                val configMsg = "X-Timestamp:$dateStr\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                val pitchStr = if (params.pitchHz >= 0) "+${params.pitchHz}Hz" else "${params.pitchHz}Hz"
                val rateStr = if (params.ratePct >= 0) "+${params.ratePct}%" else "${params.ratePct}%"
                val ssmlBody = buildSsmlBodyWithPauses(text)

                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>" +
                    "<voice name='${params.edgeVoice}'>" +
                    "<prosody pitch='$pitchStr' rate='$rateStr' volume='+0%'>$ssmlBody</prosody>" +
                    "</voice></speak>"

                val ssmlMsg = "X-RequestId:$reqId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:$dateStr\r\n" +
                    "Path:ssml\r\n\r\n$ssml"
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                    deferred.complete(audioOut.toByteArray())
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.size > 2) {
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLen
                    if (audioStart < arr.size) {
                        audioOut.write(arr, audioStart, arr.size - audioStart)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) deferred.complete(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    val data = audioOut.toByteArray()
                    deferred.complete(if (data.size > 100) data else null)
                }
            }
        })

        val mp3Bytes = withTimeoutOrNull(6500L) { deferred.await() }
        if (mp3Bytes == null || mp3Bytes.size < 100) {
            ws.cancel()
            return@withContext null
        }

        try {
            val outFile = File(cacheDir, "edge_${System.nanoTime()}.mp3")
            outFile.writeBytes(mp3Bytes)
            outFile
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun synthesizeGoogleWebMp3(text: String): File? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(text.take(190), "UTF-8")
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$q"
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
                )
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null
                val outFile = File(cacheDir, "gweb_${System.nanoTime()}.mp3")
                outFile.writeBytes(bytes)
                outFile
            }
        } catch (_: Exception) {
            null
        }
    }
}
