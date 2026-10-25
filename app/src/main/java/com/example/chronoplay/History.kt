package com.example.chronoplay

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val uri: Uri,
    val name: String,
    val minutes: Int,
    val loop: Boolean,
)

/** Most-recent-first list of played (file, duration, loop) combinations, kept in [prefs]. */
class History(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY = "history"
        const val MAX_ENTRIES = 5
    }

    fun load(): List<HistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryEntry(
                uri = o.getString("uri").toUri(),
                name = o.getString("name"),
                minutes = o.getInt("minutes"),
                loop = o.getBoolean("loop"),
            )
        }
    }.getOrDefault(emptyList())

    /** Moves [entry] to the top (dropping any identical older one) and trims the list. */
    fun add(entry: HistoryEntry): List<HistoryEntry> =
        save((listOf(entry) + load().filterNot { it.sameSettings(entry) }).take(MAX_ENTRIES))

    fun remove(entry: HistoryEntry): List<HistoryEntry> =
        save(load().filterNot { it.sameSettings(entry) })

    private fun HistoryEntry.sameSettings(other: HistoryEntry) =
        uri == other.uri && minutes == other.minutes && loop == other.loop

    private fun save(entries: List<HistoryEntry>): List<HistoryEntry> {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("minutes", it.minutes)
                    .put("loop", it.loop)
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
        return entries
    }
}
