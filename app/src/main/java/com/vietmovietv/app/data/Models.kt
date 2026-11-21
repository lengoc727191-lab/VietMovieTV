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
    val sourceLabel: String = ""
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
    val time: String = ""
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
)

data class AggregatedDetail(
    val movie: MovieDetail?,
    val servers: List<EpisodeServer>,
    val activeSources: List<String>
)
