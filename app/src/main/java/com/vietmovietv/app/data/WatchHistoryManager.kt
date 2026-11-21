package com.vietmovietv.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WatchHistoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("vietmovie_history", Context.MODE_PRIVATE)

    fun saveProgress(slug: String, serverIndex: Int, episodeIndex: Int, episodeName: String, positionMs: Long) {
        if (slug.isBlank()) return
        prefs.edit()
            .putInt("${slug}_server", serverIndex)
            .putInt("${slug}_ep_idx", episodeIndex)
            .putString("${slug}_ep_name", episodeName)
            .putLong("${slug}_pos_${serverIndex}_${episodeIndex}", positionMs)
            .apply()
    }

    fun recordWatchedMovie(
        slug: String,
        name: String,
        originName: String,
        posterUrl: String,
        thumbUrl: String,
        year: String,
        episodeName: String,
        lang: String,
        sourceLabel: String
    ) {
        if (slug.isBlank() || name.isBlank()) return
        try {
            val raw = prefs.getString("recent_movies_json", "[]") ?: "[]"
            val arr = JSONArray(raw)
            val updated = JSONArray()

            // Add newest at top
            updated.put(
                JSONObject().apply {
                    put("slug", slug)
                    put("name", name)
                    put("originName", originName)
                    put("posterUrl", posterUrl)
                    put("thumbUrl", thumbUrl)
                    put("year", year)
                    put("episodeCurrent", episodeName)
                    put("lang", lang)
                    put("sourceLabel", sourceLabel)
                }
            )

            for (i in 0 until arr.length()) {
                if (updated.length() >= 16) break
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("slug", "") != slug) {
                    updated.put(obj)
                }
            }
            prefs.edit().putString("recent_movies_json", updated.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun getRecentMovies(): List<MovieItem> {
        return try {
            val raw = prefs.getString("recent_movies_json", "[]") ?: "[]"
            val arr = JSONArray(raw)
            val list = mutableListOf<MovieItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val slug = obj.optString("slug", "")
                val name = obj.optString("name", "")
                if (slug.isBlank() || name.isBlank()) continue
                list.add(
                    MovieItem(
                        name = name,
                        slug = slug,
                        originName = obj.optString("originName", ""),
                        posterUrl = obj.optString("posterUrl", ""),
                        thumbUrl = obj.optString("thumbUrl", ""),
                        year = obj.optString("year", ""),
                        episodeCurrent = obj.optString("episodeCurrent", ""),
                        lang = obj.optString("lang", ""),
                        sourceLabel = obj.optString("sourceLabel", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getLastServerIndex(slug: String): Int = prefs.getInt("${slug}_server", 0)

    fun getLastEpisodeIndex(slug: String): Int = prefs.getInt("${slug}_ep_idx", -1)

    fun getLastEpisodeName(slug: String): String = prefs.getString("${slug}_ep_name", "") ?: ""

    fun getPosition(slug: String, serverIndex: Int, episodeIndex: Int): Long {
        return prefs.getLong("${slug}_pos_${serverIndex}_${episodeIndex}", 0L)
    }
}
