package com.vietmovietv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object MultiSourceRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                if (body.trim().startsWith("{")) JSONObject(body) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(raw: String?, cdn: String, isOphim: Boolean = false): String {
        val url = raw?.trim() ?: ""
        if (url.isEmpty()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val clean = url.trimStart('/')
        val base = cdn.trimEnd('/')
        if (isOphim && !clean.startsWith("uploads/") && !base.endsWith("uploads/movies")) {
            return "$base/uploads/movies/$clean"
        }
        return "$base/$clean"
    }

    // ==================== 1. KKPHIM (phimapi.com) ====================

    private suspend fun fetchKKPhimList(mode: String, page: Int): List<MovieItem> {
        val url = if (mode == "new") {
            "https://phimapi.com/danh-sach/phim-moi-cap-nhat?page=$page"
        } else {
            "https://phimapi.com/v1/api/danh-sach/$mode?page=$page&limit=24"
        }
        val json = fetchJson(url) ?: return emptyList()
        return parseStandardList(json, "https://phimimg.com", "KKPhim", isOphim = false)
    }

    private suspend fun searchKKPhim(keyword: String): List<MovieItem> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val json = fetchJson("https://phimapi.com/v1/api/tim-kiem?keyword=$q&limit=25") ?: return emptyList()
        return parseStandardList(json, "https://phimimg.com", "KKPhim", isOphim = false)
    }

    private suspend fun fetchKKPhimDetail(slug: String): Pair<MovieDetail?, List<EpisodeServer>> {
        val json = fetchJson("https://phimapi.com/phim/$slug") ?: return Pair(null, emptyList())
        return parseStandardDetail(json, "https://phimimg.com", "KKPhim", isOphim = false)
    }

    // ==================== 2. OPHIM (ophim1.com) ====================

    private suspend fun fetchOPhimList(mode: String, page: Int): List<MovieItem> {
        val url = if (mode == "new") {
            "https://ophim1.com/danh-sach/phim-moi-cap-nhat?page=$page"
        } else {
            "https://ophim1.com/v1/api/danh-sach/$mode?page=$page"
        }
        val json = fetchJson(url) ?: return emptyList()
        return parseStandardList(json, "https://img.ophim.live", "OPhim", isOphim = true)
    }

    private suspend fun searchOPhim(keyword: String): List<MovieItem> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val json = fetchJson("https://ophim1.com/v1/api/tim-kiem?keyword=$q") ?: return emptyList()
        return parseStandardList(json, "https://img.ophim.live", "OPhim", isOphim = true)
    }

    private suspend fun fetchOPhimDetail(slug: String): Pair<MovieDetail?, List<EpisodeServer>> {
        val json = fetchJson("https://ophim1.com/phim/$slug")
            ?: fetchJson("https://ophim1.com/v1/api/phim/$slug")
            ?: return Pair(null, emptyList())
        return parseStandardDetail(json, "https://img.ophim.live", "OPhim", isOphim = true)
    }

    // ==================== 3. NGUONC (phim.nguonc.com) ====================

    private suspend fun fetchNguonCList(mode: String, page: Int): List<MovieItem> {
        val url = when (mode) {
            "new" -> "https://phim.nguonc.com/api/films/phim-moi-cap-nhat?page=$page"
            "hoat-hinh" -> "https://phim.nguonc.com/api/films/the-loai/hoat-hinh?page=$page"
            else -> "https://phim.nguonc.com/api/films/danh-sach/$mode?page=$page"
        }
        val json = fetchJson(url) ?: return emptyList()
        return parseNguonCList(json)
    }

    private suspend fun searchNguonC(keyword: String): List<MovieItem> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val json = fetchJson("https://phim.nguonc.com/api/films/search?keyword=$q") ?: return emptyList()
        return parseNguonCList(json)
    }

    private suspend fun fetchNguonCDetail(slug: String): Pair<MovieDetail?, List<EpisodeServer>> {
        val json = fetchJson("https://phim.nguonc.com/api/film/$slug") ?: return Pair(null, emptyList())
        val movieObj = json.optJSONObject("movie") ?: return Pair(null, emptyList())

        val detail = MovieDetail(
            name = movieObj.optString("name", ""),
            slug = movieObj.optString("slug", slug),
            originName = movieObj.optString("original_name", ""),
            content = movieObj.optString("description", ""),
            posterUrl = resolveUrl(movieObj.optString("poster_url"), "https://phim.nguonc.com"),
            thumbUrl = resolveUrl(movieObj.optString("thumb_url"), "https://phim.nguonc.com"),
            year = movieObj.optString("created", "").take(4),
            episodeCurrent = movieObj.optString("current_episode", ""),
            quality = movieObj.optString("quality", ""),
            lang = movieObj.optString("language", ""),
            time = movieObj.optString("time", "")
        )

        val servers = mutableListOf<EpisodeServer>()
        val epArr = movieObj.optJSONArray("episodes") ?: JSONArray()
        for (i in 0 until epArr.length()) {
            val srvObj = epArr.optJSONObject(i) ?: continue
            val srvName = srvObj.optString("server_name", "Server ${i + 1}")
            val itemsArr = srvObj.optJSONArray("items") ?: JSONArray()
            val epList = mutableListOf<EpisodeData>()
            for (j in 0 until itemsArr.length()) {
                val epObj = itemsArr.optJSONObject(j) ?: continue
                val m3u8 = epObj.optString("m3u8", "").ifBlank { epObj.optString("link_m3u8", "") }
                if (m3u8.isNotBlank()) {
                    epList.add(
                        EpisodeData(
                            name = epObj.optString("name", "Tập ${j + 1}"),
                            slug = epObj.optString("slug", ""),
                            linkM3u8 = m3u8,
                            linkEmbed = epObj.optString("embed", "")
                        )
                    )
                }
            }
            if (epList.isNotEmpty()) {
                servers.add(EpisodeServer("[NguonC] $srvName", epList))
            }
        }
        return Pair(detail, servers)
    }

    // ==================== PARSERS ====================

    private fun parseStandardList(
        json: JSONObject,
        defaultCdn: String,
        sourceLabel: String,
        isOphim: Boolean
    ): List<MovieItem> {
        val dataObj = json.optJSONObject("data")
        val cdn = dataObj?.optString("APP_DOMAIN_CDN_IMAGE")?.takeIf { it.isNotBlank() }
            ?: json.optString("pathImage").takeIf { it.isNotBlank() }
            ?: defaultCdn

        val itemsArr = dataObj?.optJSONArray("items") ?: json.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<MovieItem>()
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            val slug = obj.optString("slug", "")
            val name = obj.optString("name", "")
            if (slug.isBlank() || name.isBlank()) continue

            val poster = resolveUrl(obj.optString("poster_url"), cdn, isOphim)
            val thumb = resolveUrl(obj.optString("thumb_url"), cdn, isOphim)
            result.add(
                MovieItem(
                    name = name,
                    slug = slug,
                    originName = obj.optString("origin_name", ""),
                    posterUrl = poster.ifBlank { thumb },
                    thumbUrl = thumb.ifBlank { poster },
                    year = obj.optString("year", ""),
                    episodeCurrent = obj.optString("episode_current", ""),
                    quality = obj.optString("quality", ""),
                    lang = obj.optString("lang", ""),
                    sourceLabel = sourceLabel
                )
            )
        }
        return result
    }

    private fun parseStandardDetail(
        json: JSONObject,
        defaultCdn: String,
        sourceLabel: String,
        isOphim: Boolean
    ): Pair<MovieDetail?, List<EpisodeServer>> {
        val dataObj = json.optJSONObject("data")
        val movieObj = json.optJSONObject("movie") ?: dataObj?.optJSONObject("item")
        val cdn = dataObj?.optString("APP_DOMAIN_CDN_IMAGE")?.takeIf { it.isNotBlank() }
            ?: json.optString("pathImage").takeIf { it.isNotBlank() }
            ?: defaultCdn

        val detail = movieObj?.let { m ->
            val poster = resolveUrl(m.optString("poster_url"), cdn, isOphim)
            val thumb = resolveUrl(m.optString("thumb_url"), cdn, isOphim)
            MovieDetail(
                name = m.optString("name", ""),
                slug = m.optString("slug", ""),
                originName = m.optString("origin_name", ""),
                content = m.optString("content", ""),
                posterUrl = poster.ifBlank { thumb },
                thumbUrl = thumb.ifBlank { poster },
                year = m.optString("year", ""),
                episodeCurrent = m.optString("episode_current", ""),
                quality = m.optString("quality", ""),
                lang = m.optString("lang", ""),
                time = m.optString("time", "")
            )
        }

        val epArr = json.optJSONArray("episodes")
            ?: movieObj?.optJSONArray("episodes")
            ?: JSONArray()

        val servers = mutableListOf<EpisodeServer>()
        for (i in 0 until epArr.length()) {
            val srvObj = epArr.optJSONObject(i) ?: continue
            val srvName = srvObj.optString("server_name", "Server ${i + 1}")
            val dataArr = srvObj.optJSONArray("server_data") ?: JSONArray()
            val epList = mutableListOf<EpisodeData>()
            for (j in 0 until dataArr.length()) {
                val epObj = dataArr.optJSONObject(j) ?: continue
                val m3u8 = epObj.optString("link_m3u8", "")
                if (m3u8.isNotBlank()) {
                    epList.add(
                        EpisodeData(
                            name = epObj.optString("name", "Tập ${j + 1}"),
                            slug = epObj.optString("slug", ""),
                            linkM3u8 = m3u8,
                            linkEmbed = epObj.optString("link_embed", "")
                        )
                    )
                }
            }
            if (epList.isNotEmpty()) {
                servers.add(EpisodeServer("[$sourceLabel] $srvName", epList))
            }
        }
        return Pair(detail, servers)
    }

    private fun parseNguonCList(json: JSONObject): List<MovieItem> {
        val itemsArr = json.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<MovieItem>()
        for (i in 0 until itemsArr.length()) {
            val obj = itemsArr.optJSONObject(i) ?: continue
            val slug = obj.optString("slug", "")
            val name = obj.optString("name", "")
            if (slug.isBlank() || name.isBlank()) continue

            val poster = resolveUrl(obj.optString("poster_url"), "https://phim.nguonc.com")
            val thumb = resolveUrl(obj.optString("thumb_url"), "https://phim.nguonc.com")
            result.add(
                MovieItem(
                    name = name,
                    slug = slug,
                    originName = obj.optString("original_name", ""),
                    posterUrl = poster.ifBlank { thumb },
                    thumbUrl = thumb.ifBlank { poster },
                    year = obj.optString("created", "").take(4),
                    episodeCurrent = obj.optString("current_episode", ""),
                    quality = obj.optString("quality", ""),
                    lang = obj.optString("language", ""),
                    sourceLabel = "NguonC"
                )
            )
        }
        return result
    }

    // ==================== PUBLIC AGGREGATED METHODS ====================

    suspend fun getMovies(source: MovieSource, mode: String, page: Int): List<MovieItem> = coroutineScope {
        when (source) {
            MovieSource.KKPHIM -> fetchKKPhimList(mode, page)
            MovieSource.OPHIM -> fetchOPhimList(mode, page)
            MovieSource.NGUONC -> fetchNguonCList(mode, page)
            MovieSource.AUTO -> {
                val results = listOf(
                    async { fetchKKPhimList(mode, page) },
                    async { fetchOPhimList(mode, page) },
                    async { fetchNguonCList(mode, page) }
                ).awaitAll()
                mergeDistinctMovies(results)
            }
        }
    }

    suspend fun searchMovies(source: MovieSource, keyword: String): List<MovieItem> = coroutineScope {
        when (source) {
            MovieSource.KKPHIM -> searchKKPhim(keyword)
            MovieSource.OPHIM -> searchOPhim(keyword)
            MovieSource.NGUONC -> searchNguonC(keyword)
            MovieSource.AUTO -> {
                val results = listOf(
                    async { searchKKPhim(keyword) },
                    async { searchOPhim(keyword) },
                    async { searchNguonC(keyword) }
                ).awaitAll()
                mergeDistinctMovies(results)
            }
        }
    }

    /**
     * Fetches detail & merges playable .m3u8 servers from KKPhim + OPhim + NguonC simultaneously!
     */
    suspend fun getAggregatedDetail(slug: String, preferredSource: String = ""): AggregatedDetail = coroutineScope {
        val kkDeferred = async { fetchKKPhimDetail(slug) }
        val opDeferred = async { fetchOPhimDetail(slug) }
        val ncDeferred = async { fetchNguonCDetail(slug) }

        val kk = kkDeferred.await()
        val op = opDeferred.await()
        val nc = ncDeferred.await()

        val ordered = when (preferredSource) {
            "OPhim" -> listOf("OPhim" to op, "KKPhim" to kk, "NguonC" to nc)
            "NguonC" -> listOf("NguonC" to nc, "KKPhim" to kk, "OPhim" to op)
            else -> listOf("KKPhim" to kk, "OPhim" to op, "NguonC" to nc)
        }

        val bestMovie = ordered.firstNotNullOfOrNull { it.second.first }
        val allServers = mutableListOf<EpisodeServer>()
        val activeSources = mutableListOf<String>()

        ordered.forEach { (srcName, pair) ->
            if (pair.second.isNotEmpty()) {
                activeSources.add(srcName)
                allServers.addAll(pair.second)
            }
        }

        AggregatedDetail(bestMovie, allServers, activeSources)
    }

    private fun mergeDistinctMovies(lists: List<List<MovieItem>>): List<MovieItem> {
        val seen = LinkedHashSet<String>()
        val merged = mutableListOf<MovieItem>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        // Interleave items from each source so user sees fresh movies from all 3 APIs
        for (idx in 0 until maxLen) {
            for (list in lists) {
                if (idx < list.size) {
                    val item = list[idx]
                    val key = item.slug.lowercase()
                    if (seen.add(key)) {
                        merged.add(item)
                    }
                }
            }
        }
        return merged
    }
}
