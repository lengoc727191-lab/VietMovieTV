package com.vietmovietv.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val spokenText: String = text,
    val gender: String = "AUTO",
    val emotion: String = "normal",
    val isDialogueSwitch: Boolean = false
)

data class SubtitleFetchResult(
    val cues: List<SubtitleCue>,
    val sourceInfo: String
)

object SubtitleRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val timeRegex = Regex("""(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val shortTimeRegex = Regex("""(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val tagRegex = Regex("""<[^>]*>|\{[^}]*\}""")

    suspend fun findVietnameseSubtitles(
        imdbIdInput: String,
        tmdbIdInput: String,
        originName: String,
        movieTitle: String,
        movieType: String,
        season: Int,
        episodeNumber: Int
    ): SubtitleFetchResult = withContext(Dispatchers.IO) {
        val isSeries = (movieType == "tv")
        var imdbId = imdbIdInput.trim()
        if (!imdbId.startsWith("tt")) {
            imdbId = lookupImdbId(originName.ifBlank { movieTitle })
        }

        if (imdbId.startsWith("tt")) {
            val stremioResult = fetchFromOpenSubtitlesV3(imdbId, isSeries, season, episodeNumber)
            if (stremioResult != null && stremioResult.cues.isNotEmpty()) {
                return@withContext stremioResult
            }
        }

        val lookupId = imdbId.takeIf { it.startsWith("tt") } ?: tmdbIdInput.trim()
        if (lookupId.isNotBlank()) {
            val wyzieVi = fetchFromWyzie(lookupId, "vi", isSeries, season, episodeNumber)
            if (wyzieVi != null && wyzieVi.cues.isNotEmpty()) {
                return@withContext wyzieVi
            }
            val wyzieEn = fetchFromWyzie(lookupId, "en", isSeries, season, episodeNumber)
            if (wyzieEn != null && wyzieEn.cues.isNotEmpty()) {
                val translated = translateCuesToVietnamese(wyzieEn.cues)
                return@withContext SubtitleFetchResult(translated, "Phụ đề EN dịch Tiếng Việt (${translated.size} câu)")
            }
        }

        SubtitleFetchResult(emptyList(), "Không tìm thấy phụ đề rời (.srt) cho tập này")
    }

    private fun lookupImdbId(query: String): String {
        val clean = query.trim()
        if (clean.isBlank()) return ""
        return try {
            val firstChar = clean.first().lowercaseChar().let { if (it in 'a'..'z') it else 'x' }
            val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
            val url = "https://v3.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"
            val body = httpGetText(url) ?: return ""
            val arr = JSONObject(body).optJSONArray("d") ?: return ""
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
                if (id.startsWith("tt")) return id
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchFromOpenSubtitlesV3(
        imdbId: String,
        isSeries: Boolean,
        season: Int,
        episodeNumber: Int
    ): SubtitleFetchResult? {
        val url = if (isSeries) {
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episodeNumber.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }
        val body = httpGetText(url) ?: return null
        return try {
            val arr = JSONObject(body).optJSONArray("subtitles") ?: return null
            var viUrl = ""
            var enUrl = ""
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val lang = item.optString("lang", "").lowercase()
                val subUrl = item.optString("url", "")
                if (subUrl.isBlank()) continue
                if ((lang == "vie" || lang == "vi" || lang.contains("viet")) && viUrl.isBlank()) {
                    viUrl = subUrl
                } else if ((lang == "eng" || lang == "en") && enUrl.isBlank()) {
                    enUrl = subUrl
                }
            }
            if (viUrl.isNotBlank()) {
                val srt = httpGetText(viUrl) ?: return null
                val cues = parseAndOptimizeSubtitles(srt)
                if (cues.isNotEmpty()) {
                    return SubtitleFetchResult(cues, "OpenSubtitles Tiếng Việt (${cues.size} câu)")
                }
            }
            if (enUrl.isNotBlank()) {
                val srt = httpGetText(enUrl) ?: return null
                val cues = parseAndOptimizeSubtitles(srt)
                if (cues.isNotEmpty()) {
                    val viCues = translateCuesToVietnamese(cues)
                    return SubtitleFetchResult(viCues, "OpenSubtitles EN dịch Tiếng Việt (${viCues.size} câu)")
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromWyzie(
        id: String,
        lang: String,
        isSeries: Boolean,
        season: Int,
        episodeNumber: Int
    ): SubtitleFetchResult? {
        val base = "https://sub.wyzie.ru/search?id=${URLEncoder.encode(id, "UTF-8")}&language=$lang"
        val url = if (isSeries) "$base&season=$season&episode=$episodeNumber" else base
        val body = httpGetText(url) ?: return null
        return try {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("url", "")
                if (subUrl.isBlank()) continue
                val srt = httpGetText(subUrl) ?: continue
                val cues = parseAndOptimizeSubtitles(srt)
                if (cues.isNotEmpty()) {
                    return SubtitleFetchResult(cues, "Kho phụ đề Tiếng Việt (${cues.size} câu)")
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun translateCuesToVietnamese(cues: List<SubtitleCue>): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        val batchSize = 25
        var idx = 0
        while (idx < cues.size) {
            val end = minOf(idx + batchSize, cues.size)
            val batch = cues.subList(idx, end)
            val joined = batch.joinToString("\n|||\n") { it.text }
            val translatedText = translateBatchFree(joined)
            val parts = translatedText?.split("|||")
            if (parts != null && parts.size == batch.size) {
                for (i in batch.indices) {
                    val vi = normalizeForVietnameseSpeech(parts[i].trim().ifBlank { batch[i].text })
                    result.add(batch[i].copy(text = vi, spokenText = vi, emotion = detectEmotionHeuristic(vi)))
                }
            } else {
                result.addAll(batch)
            }
            idx = end
        }
        return result
    }

    private fun translateBatchFree(text: String): String? {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=vi&dt=t&q=$q"
            val body = httpGetText(url) ?: return null
            val root = JSONArray(body)
            val sentences = root.optJSONArray(0) ?: return null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i)?.optString(0, "") ?: ""
                sb.append(seg)
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGetText(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "gzip")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val rawBytes = resp.body?.bytes() ?: return null
                val encoding = resp.header("Content-Encoding") ?: ""
                val bytes = if (encoding.equals("gzip", ignoreCase = true) ||
                    (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte())
                ) {
                    GZIPInputStream(ByteArrayInputStream(rawBytes)).readBytes()
                } else {
                    rawBytes
                }
                String(bytes, Charset.forName("UTF-8")).removePrefix("\uFEFF")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseAndOptimizeSubtitles(content: String): List<SubtitleCue> {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val rawCues = mutableListOf<SubtitleCue>()

        for (block in blocks) {
            val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size < 2) continue

            var timeLineIdx = -1
            var startMs = 0L
            var endMs = 0L

            for (i in lines.indices) {
                val m = timeRegex.find(lines[i])
                if (m != null) {
                    startMs = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                    endMs = toMs(m.groupValues[5], m.groupValues[6], m.groupValues[7], m.groupValues[8])
                    timeLineIdx = i
                    break
                }
                val sm = shortTimeRegex.find(lines[i])
                if (sm != null) {
                    startMs = toMs("0", sm.groupValues[1], sm.groupValues[2], sm.groupValues[3])
                    endMs = toMs("0", sm.groupValues[4], sm.groupValues[5], sm.groupValues[6])
                    timeLineIdx = i
                    break
                }
            }

            if (timeLineIdx < 0 || timeLineIdx + 1 >= lines.size) continue

            val textLines = lines.subList(timeLineIdx + 1, lines.size)
            val safeEnd = endMs.coerceAtLeast(startMs + 900L)

            val dialogueTurns = splitTwoSpeakerLines(textLines)
            if (dialogueTurns.size >= 2) {
                val totalChars = dialogueTurns.sumOf { it.length }.coerceAtLeast(1)
                val totalDur = safeEnd - startMs
                var cursor = startMs
                dialogueTurns.forEach { turnRaw ->
                    val cleaned = cleanSpokenText(turnRaw)
                    val partDur = ((totalDur * turnRaw.length) / totalChars).coerceAtLeast(650L)
                    val partEnd = (cursor + partDur).coerceAtMost(safeEnd + 300L)
                    if (cleaned.length >= 2) {
                        rawCues.add(
                            SubtitleCue(
                                startMs = cursor,
                                endMs = partEnd,
                                text = cleaned,
                                spokenText = cleaned,
                                gender = guessGenderHeuristic(cleaned),
                                emotion = detectEmotionHeuristic(cleaned),
                                isDialogueSwitch = true
                            )
                        )
                    }
                    cursor = partEnd
                }
            } else {
                val rawJoined = textLines.joinToString(" ")
                val startsWithDash = rawJoined.trimStart().startsWith("-") || rawJoined.trimStart().startsWith("–")
                val cleaned = cleanSpokenText(rawJoined)
                if (cleaned.length >= 2) {
                    rawCues.add(
                        SubtitleCue(
                            startMs = startMs,
                            endMs = safeEnd,
                            text = cleaned,
                            spokenText = cleaned,
                            gender = guessGenderHeuristic(cleaned),
                            emotion = detectEmotionHeuristic(cleaned),
                            isDialogueSwitch = startsWithDash
                        )
                    )
                }
            }
        }

        val sorted = rawCues.sortedBy { it.startMs }
        return mergeFragmentedSentences(sorted)
    }

    private fun splitTwoSpeakerLines(lines: List<String>): List<String> {
        if (lines.size < 2) return emptyList()
        val turns = mutableListOf<String>()
        var dashCount = 0
        for (l in lines) {
            val trimmed = l.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("–") || trimmed.startsWith("—")) {
                dashCount++
                turns.add(trimmed)
            } else if (turns.isNotEmpty()) {
                turns[turns.lastIndex] = turns.last() + " " + trimmed
            } else {
                turns.add(trimmed)
            }
        }
        return if (dashCount >= 2 || (dashCount == 1 && turns.size >= 2)) turns else emptyList()
    }

    private fun mergeFragmentedSentences(cues: List<SubtitleCue>): List<SubtitleCue> {
        if (cues.size <= 1) return cues
        val merged = mutableListOf<SubtitleCue>()
        var current = cues[0]

        for (i in 1 until cues.size) {
            val next = cues[i]
            val gapMs = next.startMs - current.endMs
            val combinedLen = current.text.length + 1 + next.text.length
            val totalDur = next.endMs - current.startMs

            val currTrim = current.text.trimEnd()
            val endsComplete = currTrim.endsWith(".")
                || currTrim.endsWith("!")
                || currTrim.endsWith("?")
                || currTrim.endsWith("…")
                || currTrim.endsWith(":")

            val shouldMerge = !endsComplete
                && !next.isDialogueSwitch
                && gapMs in -200L..650L
                && combinedLen <= 145
                && totalDur <= 7500L

            if (shouldMerge) {
                val joined = "${current.text} ${next.text}".replace(Regex("""\s+"""), " ").trim()
                current = current.copy(
                    endMs = next.endMs,
                    text = joined,
                    spokenText = joined,
                    gender = guessGenderHeuristic(joined),
                    emotion = detectEmotionHeuristic(joined)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    fun guessGenderHeuristic(text: String): String {
        val l = " " + text.lowercase() + " "
        return when {
            l.contains(" em yêu anh ") || l.contains(" em nhớ anh ") || l.contains(" anh ơi ") -> "F"
            l.contains(" anh yêu em ") || l.contains(" anh nhớ em ") || l.contains(" em ơi ") -> "M"
            else -> "AUTO"
        }
    }

    fun detectEmotionHeuristic(text: String): String {
        val lower = text.lowercase()
        return when {
            text.endsWith("!") || lower.contains("chạy đi") || lower.contains("cẩn thận")
                || lower.contains("dừng lại") || lower.contains("cứu") || lower.contains("mau lên") -> "urgent"
            text.contains("...") || text.contains("…") || lower.contains("xin lỗi")
                || lower.contains("buồn") || lower.contains("nhớ anh") || lower.contains("nhớ em")
                || lower.contains("vĩnh biệt") -> "sad"
            text.endsWith("?") -> "question"
            else -> "normal"
        }
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val hh = h.toLongOrNull() ?: 0L
        val mm = m.toLongOrNull() ?: 0L
        val ss = s.toLongOrNull() ?: 0L
        val msPadded = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hh * 3600L) + (mm * 60L) + ss) * 1000L + msPadded
    }

    fun normalizeForVietnameseSpeech(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        // If entire subtitle line is ALL CAPS (e.g. "CHẠY ĐI NGAY LẬP TỨC!"), convert to natural sentence case so TTS doesn't spell letters out
        val letters = s.filter { it.isLetter() }
        if (letters.length >= 6 && letters.all { it.isUpperCase() }) {
            val low = s.lowercase(Locale("vi", "VN"))
            s = low.replaceFirstChar { it.titlecase(Locale("vi", "VN")) }
        }
        s = s
            .replace(Regex("""\bTP\.\s*"""), "Thành phố ")
            .replace(Regex("""\bMr\.\s*""", RegexOption.IGNORE_CASE), "Ông ")
            .replace(Regex("""\bMrs\.\s*""", RegexOption.IGNORE_CASE), "Bà ")
            .replace(Regex("""\bDr\.\s*""", RegexOption.IGNORE_CASE), "Bác sĩ ")
            .replace("\"", "")
            .replace("*", "")
            .replace("_", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return s
    }

    fun cleanSpokenText(raw: String): String {
        val noTags = tagRegex.replace(raw, " ")
        val noBrackets = noTags.replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
        val cleaned = noBrackets
            .replace("♪", "")
            .replace("♫", "")
            .replace(Regex("""^[-–—]+\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val lower = cleaned.lowercase()
        if (lower.contains("subscene")
            || lower.contains("opensubtitles")
            || lower.contains("phudeviet")
            || lower.contains("http://")
            || lower.contains("https://")
            || lower.contains("www.")
        ) {
            return ""
        }
        return normalizeForVietnameseSpeech(cleaned)
    }
}
