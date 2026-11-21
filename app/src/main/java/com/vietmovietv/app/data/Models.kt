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

data class FilterOption(
    val slug: String,
    val label: String
)

object MovieFilters {
    val countries = listOf(
        FilterOption("", "Tất cả quốc gia"),
        FilterOption("trung-quoc", "Trung Quốc"),
        FilterOption("han-quoc", "Hàn Quốc"),
        FilterOption("au-my", "Âu Mỹ"),
        FilterOption("nhat-ban", "Nhật Bản"),
        FilterOption("viet-nam", "Việt Nam"),
        FilterOption("thai-lan", "Thái Lan"),
        FilterOption("hong-kong", "Hồng Kông"),
        FilterOption("dai-loan", "Đài Loan"),
        FilterOption("an-do", "Ấn Độ"),
        FilterOption("anh", "Anh"),
        FilterOption("phap", "Pháp")
    )

    val genres = listOf(
        FilterOption("", "Tất cả thể loại"),
        FilterOption("hanh-dong", "Hành Động"),
        FilterOption("co-trang", "Cổ Trang"),
        FilterOption("tinh-cam", "Tình Cảm"),
        FilterOption("hai-huoc", "Hài Hước"),
        FilterOption("kinh-di", "Kinh Dị"),
        FilterOption("vien-tuong", "Viễn Tưởng"),
        FilterOption("tam-ly", "Tâm Lý"),
        FilterOption("hinh-su", "Hình Sự"),
        FilterOption("vo-thuat", "Võ Thuật"),
        FilterOption("phieu-luu", "Phiêu Lưu"),
        FilterOption("chien-tranh", "Chiến Tranh"),
        FilterOption("gia-dinh", "Gia Đình"),
        FilterOption("than-thoai", "Thần Thoại"),
        FilterOption("chinh-kich", "Chính Kịch")
    )
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
    val isCinema: Boolean = false,
    val countrySlugs: List<String> = emptyList(),
    val genreSlugs: List<String> = emptyList()
) {
    fun getDubShortTag(): String {
        val l = lang.lowercase()
        return when {
            l.contains("thuyết minh") || l.contains("thuyet minh") -> "TM"
            l.contains("lồng tiếng") || l.contains("long tieng") -> "LT"
            else -> ""
        }
    }

    fun isVietnameseDubbed(): Boolean = getDubShortTag().isNotEmpty()
}

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
