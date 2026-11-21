package com.vietmovietv.app.data

enum class MovieSource(val id: String, val label: String) {
    AUTO("auto", "Tự động"),
    KKPHIM("kkphim", "KKPhim"),
    OPHIM("ophim", "OPhim"),
    NGUONC("nguonc", "NguonC");

    fun next(): MovieSource {
        val list = values()
        return list[(ordinal + 1) % list.size]
    }
}

data class MovieItem(
    val name: String,
    val slug: String,
    val originName: String = "",
    val posterUrl: String = "",
    val thumbUrl: String = "",
    val year: String = "",
    val episodeCurrent: String = "",
    val quality: String = "",
    val lang: String = "",
    val sourceLabel: String = "",
    val viewCount: Long = 0L,
    val tmdbVoteCount: Int = 0,
    val tmdbRating: Double = 0.0,
    val isCinema: Boolean = false
)

data class MovieDetail(
    val name: String,
    val slug: String,
    val originName: String = "",
    val content: String = "",
    val posterUrl: String = "",
    val thumbUrl: String = "",
    val year: String = "",
    val episodeCurrent: String = "",
    val quality: String = "",
    val lang: String = "",
    val time: String = "",
    val imdbId: String = "",
    val tmdbId: String = "",
    val movieType: String = "movie",
    val season: Int = 1
)

data class EpisodeData(
    val name: String,
    val slug: String = "",
    val linkM3u8: String = "",
    val linkEmbed: String = ""
)

data class EpisodeServer(
    val serverName: String,
    val episodes: List<EpisodeData>
) {
    fun isVietnameseDubbed(): Boolean {
        val lower = serverName.lowercase()
        return lower.contains("thuyết minh")
            || lower.contains("thuyet minh")
            || lower.contains("lồng tiếng")
            || lower.contains("long tieng")
            || lower.contains("(tm)")
            || lower.contains("(lt)")
    }
}

data class AggregatedDetail(
    val movie: MovieDetail?,
    val servers: List<EpisodeServer>,
    val activeSources: List<String>
)
