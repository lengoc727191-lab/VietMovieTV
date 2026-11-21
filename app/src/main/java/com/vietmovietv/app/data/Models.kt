package com.vietmovietv.app.data

import com.google.gson.annotations.SerializedName

data class NewMoviesResponse(
    val items: List<MovieItem>? = emptyList()
)

data class V1ListResponse(
    val data: V1Data? = null
)

data class V1Data(
    @SerializedName("APP_DOMAIN_CDN_IMAGE")
    val cdnImage: String? = "https://phimimg.com",
    val items: List<MovieItem>? = emptyList()
)

data class MovieItem(
    val name: String? = "",
    val slug: String? = "",
    @SerializedName("origin_name")
    val originName: String? = "",
    @SerializedName("poster_url")
    val posterUrl: String? = "",
    @SerializedName("thumb_url")
    val thumbUrl: String? = "",
    val year: String? = "",
    @SerializedName("episode_current")
    val episodeCurrent: String? = "",
    val quality: String? = "",
    val lang: String? = ""
)

data class MovieDetailResponse(
    val movie: MovieDetail? = null,
    val episodes: List<EpisodeServer>? = emptyList()
)

data class MovieDetail(
    val name: String? = "",
    val slug: String? = "",
    @SerializedName("origin_name")
    val originName: String? = "",
    val content: String? = "",
    @SerializedName("poster_url")
    val posterUrl: String? = "",
    @SerializedName("thumb_url")
    val thumbUrl: String? = "",
    val year: String? = "",
    @SerializedName("episode_current")
    val episodeCurrent: String? = "",
    val quality: String? = "",
    val lang: String? = "",
    val time: String? = ""
)

data class EpisodeServer(
    @SerializedName("server_name")
    val serverName: String? = "Server",
    @SerializedName("server_data")
    val serverData: List<EpisodeData>? = emptyList()
)

data class EpisodeData(
    val name: String? = "",
    val slug: String? = "",
    @SerializedName("link_embed")
    val linkEmbed: String? = "",
    @SerializedName("link_m3u8")
    val linkM3u8: String? = ""
)

fun resolveImageUrl(url: String?, cdnDomain: String? = "https://phimimg.com"): String {
    if (url.isNullOrBlank()) return ""
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val base = (cdnDomain ?: "https://phimimg.com").trimEnd('/')
    val path = url.trimStart('/')
    return "$base/$path"
}
