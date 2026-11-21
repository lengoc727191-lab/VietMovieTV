package com.vietmovietv.app.data

import android.content.Context

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

    fun getLastServerIndex(slug: String): Int = prefs.getInt("${slug}_server", 0)

    fun getLastEpisodeIndex(slug: String): Int = prefs.getInt("${slug}_ep_idx", -1)

    fun getLastEpisodeName(slug: String): String = prefs.getString("${slug}_ep_name", "") ?: ""

    fun getPosition(slug: String, serverIndex: Int, episodeIndex: Int): Long {
        return prefs.getLong("${slug}_pos_${serverIndex}_${episodeIndex}", 0L)
    }
}
