package com.vietmovietv.app.tts

import android.content.Context

class DubSettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences("vietmovie_dub_settings", Context.MODE_PRIVATE)

    fun getSavedVoiceIndex(): Int = prefs.getInt("voice_index", 0)

    fun setSavedVoiceIndex(idx: Int) {
        prefs.edit().putInt("voice_index", idx).apply()
    }
}
