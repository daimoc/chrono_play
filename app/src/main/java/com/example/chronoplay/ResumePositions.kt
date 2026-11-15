package com.example.chronoplay

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONObject

/** Name of the preferences file shared by the screen and [PlaybackService]. */
internal const val PREFS = "chronoplay"

/** Where each file last stopped, in ms, kept in [prefs] so playback can resume there. */
class ResumePositions(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY = "positions"
    }

    fun get(uri: Uri): Long = load().optLong(uri.toString(), 0L)

    /** Stores [ms] for [uri]; zero or less forgets it, so the next play starts at the beginning. */
    fun set(uri: Uri, ms: Long) {
        val positions = load()
        if (ms > 0) positions.put(uri.toString(), ms) else positions.remove(uri.toString())
        prefs.edit { putString(KEY, positions.toString()) }
    }

    fun remove(uri: Uri) = set(uri, 0L)

    private fun load(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
}
