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

enum class TtsBackend {
    EDGE_NEURAL,
    GOOGLE_SYSTEM,
    GOOGLE_TRANSLATE_WEB
}

data class VoiceProfile(
    val id: String,
    val label: String,
    val backend: TtsBackend,
    val edgeVoiceName: String = "vi-VN-HoaiMyNeural",
    val edgePitch: String = "+0Hz",
    val edgeRate: String = "+14%",
    val systemVoiceName: String? = null,
    val systemPitch: Float = 1.0f,
    val systemRate: Float = 1.15f
)

class AiDubbingEngine(
    private val context: Context,
    private val onDuckAudio: (Boolean) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheDir = File(context.cacheDir, "ai_dub_tts").apply { mkdirs() }
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

    private val restoreAudioRunnable = Runnable {
        if (!isSpeaking) {
            onDuckAudio(false)
        }
    }

    private val voices = mutableListOf(
        VoiceProfile(
            id = "edge_hoaimy",
            label = "Hoài My (Edge Nữ)",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            edgePitch = "+0Hz",
            edgeRate = "+14%"
        ),
        VoiceProfile(
            id = "edge_namminh",
            label = "Nam Minh (Edge Nam)",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-NamMinhNeural",
            edgePitch = "+0Hz",
            edgeRate = "+14%"
        ),
        VoiceProfile(
            id = "edge_nu_am",
            label = "Nữ Trầm Ấm (Edge)",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-HoaiMyNeural",
            edgePitch = "-8Hz",
            edgeRate = "+8%"
        ),
        VoiceProfile(
            id = "edge_nam_tram",
            label = "Nam Trầm (Edge)",
            backend = TtsBackend.EDGE_NEURAL,
            edgeVoiceName = "vi-VN-NamMinhNeural",
            edgePitch = "-6Hz",
            edgeRate = "+8%"
        ),
        VoiceProfile(
            id = "google_web_vi",
            label = "Google Nữ (Online)",
            backend = TtsBackend.GOOGLE_TRANSLATE_WEB
        ),
        VoiceProfile(
            id = "google_sys_default",
            label = "Google TTS (TV)",
            backend = TtsBackend.GOOGLE_SYSTEM,
            systemVoiceName = null,
            systemPitch = 1.0f,
            systemRate = 1.15f
        )
    )

    private var currentVoiceIndex = 0

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
                                            label = "Google TV Giọng ${idx + 1}",
                                            backend = TtsBackend.GOOGLE_SYSTEM,
                                            systemVoiceName = v.name,
                                            systemPitch = 1.0f,
                                            systemRate = 1.15f
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            markSpeakingStart()
                        }

                        override fun onDone(utteranceId: String?) {
                            markSpeakingEnd()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            markSpeakingEnd()
                        }
                    })
                }
            }
        } catch (_: Exception) {
            androidTtsReady = false
        }
    }

    fun getCurrentVoice(): VoiceProfile = synchronized(voices) {
        voices[currentVoiceIndex.coerceIn(voices.indices)]
    }

    fun nextVoice(): VoiceProfile = synchronized(voices) {
        currentVoiceIndex = (currentVoiceIndex + 1) % voices.size
        edgeFailStreak = 0
        clearPreloadCache()
        voices[currentVoiceIndex]
    }

    fun preloadText(text: String) {
        val voice = getCurrentVoice()
        if (voice.backend == TtsBackend.GOOGLE_SYSTEM) return
        val clean = text.trim()
        if (clean.length < 2) return
        val key = "${voice.id}_${clean.hashCode()}"
        if (preloadedFiles.containsKey(key)) return

        scope.launch {
            val file = synthesizeAudioFile(clean, voice)
            if (file != null && file.length() > 100L) {
                preloadedFiles[key] = file
            }
        }
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.length < 2) return

        currentSpeakJob?.cancel()
        val voice = getCurrentVoice()

        currentSpeakJob = scope.launch {
            when (voice.backend) {
                TtsBackend.GOOGLE_SYSTEM -> {
                    if (androidTtsReady) {
                        speakViaAndroidTts(clean, voice)
                    } else {
                        val fallbackFile = synthesizeGoogleWebMp3(clean)
                        if (fallbackFile != null) playAudioFile(fallbackFile)
                    }
                }
                TtsBackend.GOOGLE_TRANSLATE_WEB -> {
                    val key = "${voice.id}_${clean.hashCode()}"
                    val file = preloadedFiles.remove(key) ?: synthesizeGoogleWebMp3(clean)
                    if (file != null) {
                        playAudioFile(file)
                    } else if (androidTtsReady) {
                        speakViaAndroidTts(clean, voice)
                    }
                }
                TtsBackend.EDGE_NEURAL -> {
                    val key = "${voice.id}_${clean.hashCode()}"
                    val cached = preloadedFiles.remove(key)
                    val file = cached ?: if (edgeFailStreak < 3) {
                        val edgeFile = synthesizeEdgeMp3(clean, voice)
                        if (edgeFile != null) {
                            edgeFailStreak = 0
                            edgeFile
                        } else {
                            edgeFailStreak++
                            synthesizeGoogleWebMp3(clean)
                        }
                    } else {
                        synthesizeGoogleWebMp3(clean)
                    }

                    if (file != null) {
                        playAudioFile(file)
                    } else if (androidTtsReady) {
                        speakViaAndroidTts(clean, voice)
                    }
                }
            }
        }
    }

    fun stopCurrentSpeech() {
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
            onDuckAudio(false)
        }
    }

    fun release() {
        stopCurrentSpeech()
        scope.cancel()
        clearPreloadCache()
        try {
            androidTts?.shutdown()
            androidTts = null
        } catch (_: Exception) {
        }
    }

    private fun clearPreloadCache() {
        preloadedFiles.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun markSpeakingStart() {
        mainHandler.removeCallbacks(restoreAudioRunnable)
        isSpeaking = true
        mainHandler.post { onDuckAudio(true) }
    }

    private fun markSpeakingEnd() {
        isSpeaking = false
        mainHandler.removeCallbacks(restoreAudioRunnable)
        mainHandler.postDelayed(restoreAudioRunnable, 380L)
    }

    private fun speakViaAndroidTts(text: String, voice: VoiceProfile) {
        mainHandler.post {
            val tts = androidTts ?: return@post
            try {
                tts.setPitch(voice.systemPitch)
                tts.setSpeechRate(voice.systemRate)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && voice.systemVoiceName != null) {
                    val matched: Voice? = tts.voices?.firstOrNull { it.name == voice.systemVoiceName }
                    if (matched != null) tts.voice = matched
                }
                markSpeakingStart()
                val uid = "dub_${System.currentTimeMillis()}"
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
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

    private suspend fun synthesizeAudioFile(text: String, voice: VoiceProfile): File? {
        return when (voice.backend) {
            TtsBackend.EDGE_NEURAL -> synthesizeEdgeMp3(text, voice) ?: synthesizeGoogleWebMp3(text)
            TtsBackend.GOOGLE_TRANSLATE_WEB -> synthesizeGoogleWebMp3(text)
            TtsBackend.GOOGLE_SYSTEM -> null
        }
    }

    // ==================== MICROSOFT EDGE NEURAL TTS (FREE) ====================

    private fun generateSecMsGec(): String {
        val winEpoch = 11644473600L
        var ticks = (System.currentTimeMillis() / 1000L) + winEpoch
        ticks -= (ticks % 300L)
        ticks *= 10_000_000L
        val raw = "${ticks}6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private suspend fun synthesizeEdgeMp3(text: String, voice: VoiceProfile): File? = withContext(Dispatchers.IO) {
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

                val safeText = escapeXml(text.take(350))
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>" +
                    "<voice name='${voice.edgeVoiceName}'>" +
                    "<prosody pitch='${voice.edgePitch}' rate='${voice.edgeRate}' volume='+0%'>$safeText</prosody>" +
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

    // ==================== GOOGLE TRANSLATE WEB TTS FALLBACK (FREE) ====================

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
